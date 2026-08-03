package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;

/**
 * 报告生命周期模块接收的不可变草稿。
 */
public record ConversationReportDraft(
        ConversationIdentity identity,
        String content,
        boolean needsRag,
        PsychologyAssessment assessment,
        MultimodalAnalysis multimodalAnalysis
) {
}
