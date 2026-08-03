package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.dto.ChatStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 对话模型输出模块的外部接缝。
 */
public interface ConversationResponseStreamer {

    Flux<ServerSentEvent<ChatStreamEvent>> stream(PreparedConversation prepared);
}
