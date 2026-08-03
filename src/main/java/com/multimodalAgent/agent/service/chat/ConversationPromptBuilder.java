package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.routing.RoutingDecision;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 将路由结果、RAG 上下文和历史消息组装为模型输入。
 */
@Service
public class ConversationPromptBuilder {

    public List<AiMessage> build(
            ConversationIdentity identity,
            RoutingDecision routing,
            AgenticRagResult ragResult,
            List<AiMessage> history
    ) {
        // Agentic RAG 计划和证据作为低权限运行时上下文，不直接展示后台评估信息给学生。
        String context = ragResult.answerContext(routing.riskLevel());
        List<AiMessage> messages = new ArrayList<>(PromptTemplates.answerPrompt(
                routing.needsRag(),
                routing.riskLevel(),
                context,
                identity.username()));

        messages.addAll(history);
        return List.copyOf(messages);
    }
}
