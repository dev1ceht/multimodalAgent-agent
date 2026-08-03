package com.multimodalAgent.agent.service.chat;

import java.util.List;

/**
 * 经过窗口裁剪的会话历史快照。
 *
 * <p>该类型只表达对话领域语义，不暴露具体模型的消息格式。</p>
 */
public record ConversationHistory(List<ConversationMessage> messages) {

    public ConversationHistory {
        messages = List.copyOf(messages);
    }
}
