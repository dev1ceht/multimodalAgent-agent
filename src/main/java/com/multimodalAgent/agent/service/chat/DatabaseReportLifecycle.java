package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.ToolOrchestrationService;
import org.springframework.stereotype.Service;

/**
 * 报告持久化与投递任务创建的数据库适配器。
 */
@Service
public class DatabaseReportLifecycle implements ReportLifecycle {

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ToolOrchestrationService toolOrchestrationService;

    public DatabaseReportLifecycle(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ToolOrchestrationService toolOrchestrationService
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.toolOrchestrationService = toolOrchestrationService;
    }

    @Override
    public void persist(ConversationReportDraft draft) {
        ConversationIdentity identity = draft.identity();
        UserAccount user = userAccountRepository.findById(identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = chatSessionRepository
                .findByPublicIdAndUser_Id(identity.sessionPublicId(), identity.userId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSession(session);
        report.setContent(draft.content());
        report.setNeedsRag(draft.needsRag());
        report.setEmotion(draft.assessment().emotion());
        report.setEmotionScore(draft.assessment().emotionScore());
        report.setRiskLevel(draft.assessment().risk());
        report.setConfidence(draft.assessment().confidence());
        report.setSummary(draft.assessment().summary());
        if (draft.multimodalAnalysis() != null) {
            report.setEmotionTags(draft.multimodalAnalysis().emotionTagsJson());
        }
        toolOrchestrationService.saveReportAndEnqueue(report);
    }
}
