package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.service.chat.ConversationPreparation;
import com.multimodalAgent.agent.service.chat.ConversationRequest;
import com.multimodalAgent.agent.service.chat.ConversationResponseStreamer;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 学生聊天主流程入口。
 *
 * <p>该类只负责把 Web 请求交给对话准备模块，再交给模型输出模块；
 * 会话记忆、风险决策、报告投递和 SSE 细节都隐藏在各自的深模块中。</p>
 */
@Service
public class ChatService {

    private final ConversationPreparation conversationPreparation;
    private final ConversationResponseStreamer conversationResponseStreamer;

    public ChatService(
            ConversationPreparation conversationPreparation,
            ConversationResponseStreamer conversationResponseStreamer
    ) {
        this.conversationPreparation = conversationPreparation;
        this.conversationResponseStreamer = conversationResponseStreamer;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        return stream(new ConversationRequest(userId, request, null));
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(
            Long userId,
            ChatRequest request,
            MultimodalAnalysis analysis
    ) {
        return stream(new ConversationRequest(userId, request, analysis));
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> stream(ConversationRequest request) {
        // 数据库读写和准备阶段的阻塞调用放到 boundedElastic，避免阻塞响应线程。
        return Mono.fromCallable(() -> conversationPreparation.prepare(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(conversationResponseStreamer::stream)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
