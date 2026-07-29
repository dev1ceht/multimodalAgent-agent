package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService.Trace;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import com.multimodalAgent.agent.service.routing.RequestRouter;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
/**
 * 学生聊天主流程服务。
 *
 * <p>负责会话管理、Redis 短期记忆、MySQL 长期记忆、请求路由、RAG 检索、模型流式调用和后台报告触发。</p>
 */
public class ChatService {

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PsychologicalReportRepository reportRepository;
    private final multimodalAgentProperties properties;
    private final RequestRouter requestRouter;
    private final PsychologicalAssessmentService assessmentService;
    private final AgenticRagService agenticRagService;
    private final ToolOrchestrationService toolOrchestrationService;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;
    private final AiClient aiClient;
    private final EvaluationTraceService evaluationTraceService;

    public ChatService(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            PsychologicalReportRepository reportRepository,
            multimodalAgentProperties properties,
            RequestRouter requestRouter,
            PsychologicalAssessmentService assessmentService,
            AgenticRagService agenticRagService,
            ToolOrchestrationService toolOrchestrationService,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService,
            AiClient aiClient,
            EvaluationTraceService evaluationTraceService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.requestRouter = requestRouter;
        this.assessmentService = assessmentService;
        this.agenticRagService = agenticRagService;
        this.toolOrchestrationService = toolOrchestrationService;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
        this.aiClient = aiClient;
        this.evaluationTraceService = evaluationTraceService;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        // 聊天接口使用 SSE 流式返回；数据库读写放到 boundedElastic，避免阻塞响应线程。
        return Mono.fromCallable(() -> prepare(userId, request, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(Long userId, ChatRequest request, MultimodalAnalysis analysis) {
        return Mono.fromCallable(() -> prepare(userId, request, analysis))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    private PreparedConversation prepare(Long userId, ChatRequest request, MultimodalAnalysis multimodalAnalysis) {
        long prepareStarted = System.nanoTime();
        String input = request.message().trim();
        String modelInput = privacySanitizer.sanitize(multimodalAnalysis == null ? input : multimodalAnalysis.modelText());
        Trace trace = evaluationTraceService.start(request.evaluationId(), configuredModel(), modelInput);
        evaluationTraceService.bind(trace);
        try {
            UserAccount user = userAccountRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));
            ChatSession session = resolveSession(user, request.sessionId(), input);
            List<AiMessage> previousHistory = recentModelHistory(session);
            saveMessage(user, session, MessageRole.USER, input);
            if (multimodalAnalysis != null) {
                saveMultimodalMemory(user, session, multimodalAnalysis);
            }

            List<AiMessage> modelHistory = withCurrentUser(previousHistory, modelInput);
            RiskLevel externalRisk = multimodalAnalysis == null
                    ? RiskLevel.NONE
                    : multimodalAnalysis.fusedAssessment().risk();
            RoutingDecision routing = requestRouter.decide(modelInput, previousHistory, externalRisk);
            PsychologyAssessment assessment = null;
            AgenticRagResult ragResult = AgenticRagResult.empty();
            PsychologicalReport report = null;

            if (routing.needsRag()) {
                ragResult = agenticRagService.retrieve(modelInput, previousHistory);
            }

            // 只有中高风险才生成敏感心理报告；知识问答和轻度支持不因使用 RAG 而自动建档。
            if (routing.riskLevel().ordinal() >= RiskLevel.MEDIUM.ordinal()) {
                assessment = multimodalAnalysis == null
                        ? assessmentService.assess(modelInput, previousHistory)
                        : multimodalAnalysis.fusedAssessment();
                RiskLevel finalRisk = higherRisk(routing.riskLevel(), assessment.risk());
                if (finalRisk.ordinal() > routing.riskLevel().ordinal()) {
                    routing = routing.withRiskFloor(finalRisk, "心理评估提高风险等级");
                }
                assessment = withRiskFloor(assessment, routing.riskLevel());
                report = saveReport(user, session, input, routing.needsRag(), assessment, multimodalAnalysis);
            }

            List<AiMessage> messages = buildMessages(user, routing, ragResult, modelHistory);
            Long reportId = report == null ? null : report.getId();
            evaluationTraceService.put("finalNeedsRag", routing.needsRag());
            evaluationTraceService.put("finalRisk", routing.riskLevel().name());
            evaluationTraceService.duration("prepareMs", prepareStarted);
            return new PreparedConversation(user, session, messages, reportId, trace);
        } catch (RuntimeException exception) {
            evaluationTraceService.finish(trace, "error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw exception;
        } finally {
            evaluationTraceService.unbind();
        }
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamPrepared(PreparedConversation prepared) {
        long generationStarted = System.nanoTime();
        StringBuilder assistantReply = new StringBuilder();
        AtomicBoolean firstToken = new AtomicBoolean();
        AtomicReference<String> streamError = new AtomicReference<>();
        Flux<ServerSentEvent<ChatStreamEvent>> meta = Flux.just(event(
                "meta",
                ChatStreamEvent.meta(prepared.session().getPublicId())));

        Flux<ServerSentEvent<ChatStreamEvent>> tokens = aiClient.stream(prepared.messages())
                .doOnNext(token -> {
                    if (firstToken.compareAndSet(false, true)) {
                        evaluationTraceService.put(
                                prepared.trace(),
                                "ttftMs",
                                prepared.trace() == null ? null : prepared.trace().elapsedMillis());
                    }
                    assistantReply.append(token);
                })
                .map(token -> event("token", ChatStreamEvent.token(prepared.session().getPublicId(), token)))
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(exception -> {
                    streamError.set(exception.getClass().getSimpleName());
                    return Flux.just(event(
                            "error",
                            ChatStreamEvent.error(prepared.session().getPublicId(), "模型响应超时或失败，请稍后重试。")));
                })
                .switchIfEmpty(Flux.defer(() -> {
                    streamError.set("EmptyModelResponse");
                    return Flux.just(event(
                            "error",
                            ChatStreamEvent.error(prepared.session().getPublicId(), "模型没有返回内容，请稍后重试。")));
                }));

        Mono<ServerSentEvent<ChatStreamEvent>> done = Mono.fromCallable(() -> {
            try {
                if (!assistantReply.isEmpty()) {
                    saveMessage(prepared.user(), prepared.session(), MessageRole.ASSISTANT, assistantReply.toString());
                }
                // 工具链在模型回复完成后异步执行，不打断学生端正在进行的对话体验。
                if (prepared.reportId() != null) {
                    toolOrchestrationService.handleAsync(prepared.reportId());
                }
                evaluationTraceService.duration(prepared.trace(), "generationMs", generationStarted);
                evaluationTraceService.put(prepared.trace(), "outputChars", assistantReply.length());
                String error = streamError.get();
                evaluationTraceService.finish(prepared.trace(), error == null ? "success" : "error", error);
                return event("done", ChatStreamEvent.done(prepared.session().getPublicId()));
            } catch (RuntimeException exception) {
                evaluationTraceService.finish(
                        prepared.trace(),
                        "error",
                        exception.getClass().getSimpleName() + ": " + exception.getMessage());
                throw exception;
            }
        }).subscribeOn(Schedulers.boundedElastic());

        return meta.concatWith(tokens).concatWith(done);
    }

    private String configuredModel() {
        return switch (properties.getAi().getProvider().toLowerCase()) {
            case "ollama" -> properties.getAi().getOllama().getModel();
            case "openai" -> properties.getAi().getOpenai().getModel();
            default -> properties.getAi().getProvider();
        };
    }

    private ChatSession resolveSession(UserAccount user, String publicId, String input) {
        if (publicId != null && !publicId.isBlank()) {
            return chatSessionRepository.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setTitle(input.length() > 36 ? input.substring(0, 36) : input);
        return chatSessionRepository.save(session);
    }

    private void saveMessage(UserAccount user, ChatSession session, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
        shortTermMemoryService.append(session.getPublicId(), role, content);
    }

    private void saveMultimodalMemory(UserAccount user, ChatSession session, MultimodalAnalysis analysis) {
        saveMessage(user, session, MessageRole.SYSTEM, multimodalMemory(analysis));
    }

    private String multimodalMemory(MultimodalAnalysis analysis) {
        String modalities = String.join("、", analysis.signals().stream()
                .map(MultimodalSignal::modality)
                .distinct()
                .toList());
        String evidence = String.join("；", analysis.signals().stream()
                .map(signal -> signal.modality() + "=" + signal.evidence())
                .toList());
        return """
                【多模态分析记忆】
                用户本轮上传了%s，后端已完成多模态情绪分析。后续如果用户追问“你是否根据图片/语音/视频分析”，应说明：我是基于后端多模态分析结果和你的文字一起判断，不是只凭文字猜测。不要否认已上传附件，也不要声称自己直接查看了原始文件。
                分析摘要：%s
                情绪标签：%s
                分析证据：%s
                """.formatted(
                modalities.isBlank() ? "附件" : modalities,
                analysis.summary(),
                analysis.emotionTagsJson(),
                evidence.isBlank() ? "无" : evidence);
    }

    private PsychologicalReport saveReport(
            UserAccount user,
            ChatSession session,
            String content,
            boolean needsRag,
            PsychologyAssessment assessment,
            MultimodalAnalysis multimodalAnalysis
    ) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSession(session);
        report.setContent(content);
        report.setNeedsRag(needsRag);
        report.setEmotion(assessment.emotion());
        report.setEmotionScore(assessment.emotionScore());
        report.setRiskLevel(assessment.risk());
        report.setConfidence(assessment.confidence());
        report.setSummary(assessment.summary());
        if (multimodalAnalysis != null) {
            report.setEmotionTags(multimodalAnalysis.emotionTagsJson());
        }
        return reportRepository.save(report);
    }

    private List<AiMessage> buildMessages(
            UserAccount user,
            RoutingDecision routing,
            AgenticRagResult ragResult,
            List<AiMessage> history
    ) {
        // Agentic RAG 计划和证据作为低权限运行时上下文，不直接展示后台评估信息给学生。
        String context = ragResult.answerContext(routing.riskLevel());
        List<AiMessage> messages = new ArrayList<>();
        messages.addAll(PromptTemplates.answerPrompt(
                routing.needsRag(),
                routing.riskLevel(),
                context,
                user.getUsername()));

        int limit = messageWindowLimit();
        history.stream()
                .skip(Math.max(0, history.size() - limit))
                .forEach(messages::add);
        return messages;
    }

    private List<ChatMessage> recentHistory(ChatSession session) {
        List<ChatMessage> history = chatMessageRepository.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);
        return history;
    }

    private List<AiMessage> recentModelHistory(ChatSession session) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(session.getPublicId());
        if (!redisHistory.isEmpty()) {
            return redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
        }

        // Redis 中没有短期记忆时，从 MySQL 长期记忆恢复最近 10 轮上下文。
        List<ChatMessage> databaseHistory = recentHistory(session);
        shortTermMemoryService.refresh(session.getPublicId(), databaseHistory.stream()
                .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                .toList());
        return databaseHistory.stream()
                .map(this::toAiMessage)
                .toList();
    }

    private List<AiMessage> withCurrentUser(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        int limit = messageWindowLimit();
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    private int messageWindowLimit() {
        // history-limit 以轮次理解，这里乘 2 保留用户和助手两侧消息。
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> PromptTemplates.multimodalContext(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> PromptTemplates.multimodalContext(content);
            case USER -> AiMessage.user(content);
        };
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private RiskLevel higherRisk(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private PsychologyAssessment withRiskFloor(PsychologyAssessment assessment, RiskLevel floor) {
        return assessment.withRiskFloor(floor);
    }

    private record PreparedConversation(
            UserAccount user,
            ChatSession session,
            List<AiMessage> messages,
            Long reportId,
            Trace trace
    ) {
    }
}
