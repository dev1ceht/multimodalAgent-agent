package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import java.util.List;

/**
 * 对话记忆模块的接口。
 *
 * <p>调用方不需要知道 Redis 优先、数据库回退、消息窗口、清洗和持久化细节。</p>
 */
public interface ConversationMemory {

    ConversationIdentity open(Long userId, String sessionPublicId, String title);

    List<AiMessage> recentModelHistory(ConversationIdentity identity);

    List<AiMessage> withCurrentInput(List<AiMessage> previousHistory, String currentInput);

    void append(ConversationIdentity identity, MessageRole role, String content);

    void appendMultimodalMemory(ConversationIdentity identity, MultimodalAnalysis analysis);
}
