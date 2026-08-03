package com.multimodalAgent.agent.service.chat;

/**
 * 流式阶段使用的会话快照。
 *
 * <p>只跨越模块传递标识和值，不传递 JPA 实体，避免异步流式阶段依赖已关闭的持久化上下文。</p>
 */
public record ConversationIdentity(
        Long userId,
        Long sessionId,
        String sessionPublicId,
        String username
) {
}
