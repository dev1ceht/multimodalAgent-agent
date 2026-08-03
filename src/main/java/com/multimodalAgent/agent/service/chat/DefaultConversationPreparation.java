package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService.Trace;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 对话准备模块的默认实现。
 */
@Service
public class DefaultConversationPreparation implements ConversationPreparation {

    private final multimodalAgentProperties properties;
    private final ConversationMemory conversationMemory;
    private final ConversationDecision conversationDecision;
    private final ConversationPromptBuilder promptBuilder;
    private final PrivacySanitizer privacySanitizer;
    private final EvaluationTraceService evaluationTraceService;

    public DefaultConversationPreparation(
            multimodalAgentProperties properties,
            ConversationMemory conversationMemory,
            ConversationDecision conversationDecision,
            ConversationPromptBuilder promptBuilder,
            PrivacySanitizer privacySanitizer,
            EvaluationTraceService evaluationTraceService
    ) {
        this.properties = properties;
        this.conversationMemory = conversationMemory;
        this.conversationDecision = conversationDecision;
        this.promptBuilder = promptBuilder;
        this.privacySanitizer = privacySanitizer;
        this.evaluationTraceService = evaluationTraceService;
    }

    @Override
    public PreparedConversation prepare(ConversationRequest request) {
        long prepareStarted = System.nanoTime();
        String input = request.input();
        String modelInput = privacySanitizer.sanitize(
                request.multimodalAnalysis() == null
                        ? input
                        : request.multimodalAnalysis().modelText());
        Trace trace = evaluationTraceService.start(
                request.request().evaluationId(),
                configuredModel(),
                modelInput);
        evaluationTraceService.bind(trace);
        try {
            ConversationIdentity identity = conversationMemory.open(
                    request.userId(),
                    request.request().sessionId(),
                    input);
            List<AiMessage> previousHistory =
                    conversationMemory.recentModelHistory(identity);
            List<AiMessage> modelHistory =
                    conversationMemory.withCurrentInput(previousHistory, modelInput);
            conversationMemory.append(identity, MessageRole.USER, input);
            if (request.multimodalAnalysis() != null) {
                conversationMemory.appendMultimodalMemory(identity, request.multimodalAnalysis());
            }

            ConversationDecisionResult decision = conversationDecision.decide(
                    new ConversationDecisionInput(
                            identity,
                            input,
                            modelInput,
                            previousHistory,
                            request.multimodalAnalysis()));
            List<AiMessage> messages = promptBuilder.build(
                    identity,
                    decision.routing(),
                    decision.ragResult(),
                    modelHistory);

            evaluationTraceService.put("finalNeedsRag", decision.routing().needsRag());
            evaluationTraceService.put("finalRisk", decision.routing().riskLevel().name());
            evaluationTraceService.duration("prepareMs", prepareStarted);
            return new PreparedConversation(identity, messages, trace);
        } catch (RuntimeException exception) {
            evaluationTraceService.finish(
                    trace,
                    "error",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
            throw exception;
        } finally {
            evaluationTraceService.unbind();
        }
    }

    private String configuredModel() {
        return switch (properties.getAi().getProvider().toLowerCase()) {
            case "ollama" -> properties.getAi().getOllama().getModel();
            case "openai" -> properties.getAi().getOpenai().getModel();
            default -> properties.getAi().getProvider();
        };
    }
}
