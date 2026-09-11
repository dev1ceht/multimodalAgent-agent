package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.dto.ChatStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * SAA 对话编排入口。
 *
 * <p>Web 层只依赖这个边界，具体的会话准备、路由、Agent 运行时和收尾副作用由
 * {@link AgentConversationService} 负责。</p>
 */
public interface AgentConversationGateway {

    Flux<ServerSentEvent<ChatStreamEvent>> stream(
            ConversationRequest request,
            boolean consentValidated
    );
}
