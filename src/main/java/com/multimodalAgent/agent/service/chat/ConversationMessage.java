package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.MessageRole;

/**
 * 与模型供应商无关的一条会话历史消息。
 */
public record ConversationMessage(
        MessageRole role,
        String content
) {
}
