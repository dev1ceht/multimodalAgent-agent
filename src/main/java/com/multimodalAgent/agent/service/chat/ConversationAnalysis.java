package com.multimodalAgent.agent.service.chat;

/**
 * 对话分析模块的外部接缝。
 */
public interface ConversationAnalysis {

    ConversationAnalysisResult analyze(ConversationAnalysisInput input);
}
