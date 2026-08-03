package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话模型输出模块的默认实现。
 *
 * <p>模型输出完成后才保存助手消息；报告和投递任务已经在准备阶段独立落库。</p>
 */
@Service
public class DefaultConversationResponseStreamer implements ConversationResponseStreamer {

    private final ConversationMemory conversationMemory;
    private final AiClient aiClient;
    private final EvaluationTraceService evaluationTraceService;

    public DefaultConversationResponseStreamer(
            ConversationMemory conversationMemory,
            AiClient aiClient,
            EvaluationTraceService evaluationTraceService
    ) {
        this.conversationMemory = conversationMemory;
        this.aiClient = aiClient;
        this.evaluationTraceService = evaluationTraceService;
    }

    @Override
    public Flux<ServerSentEvent<ChatStreamEvent>> stream(PreparedConversation prepared) {
        long generationStarted = System.nanoTime();
        StringBuilder assistantReply = new StringBuilder();
        AtomicBoolean firstToken = new AtomicBoolean();
        AtomicReference<String> streamError = new AtomicReference<>();
        String sessionPublicId = prepared.identity().sessionPublicId();
        Flux<ServerSentEvent<ChatStreamEvent>> meta = Flux.just(event(
                "meta",
                ChatStreamEvent.meta(sessionPublicId)));

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
                .map(token -> event("token", ChatStreamEvent.token(sessionPublicId, token)))
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(exception -> {
                    streamError.set(exception.getClass().getSimpleName());
                    return Flux.just(event(
                            "error",
                            ChatStreamEvent.error(sessionPublicId, "模型响应超时或失败，请稍后重试。")));
                })
                .switchIfEmpty(Flux.defer(() -> {
                    streamError.set("EmptyModelResponse");
                    return Flux.just(event(
                            "error",
                            ChatStreamEvent.error(sessionPublicId, "模型没有返回内容，请稍后重试。")));
                }));

        Mono<ServerSentEvent<ChatStreamEvent>> done = Mono.fromCallable(() -> {
            try {
                if (!assistantReply.isEmpty()) {
                    conversationMemory.append(
                            prepared.identity(),
                            MessageRole.ASSISTANT,
                            assistantReply.toString());
                }
                // 投递任务已在报告落库时创建，不依赖 SSE 完成事件。
                evaluationTraceService.duration(prepared.trace(), "generationMs", generationStarted);
                evaluationTraceService.put(prepared.trace(), "outputChars", assistantReply.length());
                String error = streamError.get();
                evaluationTraceService.finish(prepared.trace(), error == null ? "success" : "error", error);
                return event("done", ChatStreamEvent.done(sessionPublicId));
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

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
