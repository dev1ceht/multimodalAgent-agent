package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;

/**
 * 对话准备模块接收的统一输入。
 *
 * <p>Web 层可以继续使用 {@link ChatRequest}，内部流程只依赖这个不可变输入，
 * 因此多模态和普通聊天共享同一条准备与流式输出链路。</p>
 */
public record ConversationRequest(
        Long userId,
        ChatRequest request,
        MultimodalAnalysis multimodalAnalysis
) {

    public String input() {
        return request.message().trim();
    }
}
