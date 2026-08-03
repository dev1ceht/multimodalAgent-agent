package com.multimodalAgent.agent.service.chat;

/**
 * 对话准备模块的外部接缝。
 */
public interface ConversationPreparation {

    PreparedConversation prepare(ConversationRequest request);
}
