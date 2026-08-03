package com.multimodalAgent.agent.service.chat;

/**
 * 对话报告生命周期的外部接缝。
 */
public interface ReportLifecycle {

    void persist(ConversationReportDraft draft);
}
