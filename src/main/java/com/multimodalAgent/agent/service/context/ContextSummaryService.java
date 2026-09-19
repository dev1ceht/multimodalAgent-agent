package com.multimodalAgent.agent.service.context;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ConversationContextJob;
import com.multimodalAgent.agent.domain.ConversationContextSummary;
import com.multimodalAgent.agent.repository.ConversationContextJobRepository;
import com.multimodalAgent.agent.repository.ConversationContextSummaryRepository;
import com.multimodalAgent.agent.service.chat.ConversationIdentity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the small enqueue/read seam for asynchronous per-session context summaries. */
@Service
public class ContextSummaryService {

    private final ConversationContextSummaryRepository summaries;
    private final ConversationContextJobRepository jobs;
    private final multimodalAgentProperties properties;

    public ContextSummaryService(
            ConversationContextSummaryRepository summaries,
            ConversationContextJobRepository jobs,
            multimodalAgentProperties properties
    ) {
        this.summaries = summaries;
        this.jobs = jobs;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Optional<ConversationContextSummary> current(ConversationIdentity identity) {
        if (!isEnabled() || identity == null || identity.sessionId() == null || identity.userId() == null) {
            return Optional.empty();
        }
        return summaries.findBySessionIdAndUserId(identity.sessionId(), identity.userId())
                .filter(summary -> "conversation-summary-v1".equals(summary.getSchemaVersion())
                        && summary.getSummaryJson() != null
                        && !summary.getSummaryJson().isBlank()
                        && summary.getCoveredThroughMessageId() != null
                        && summary.getCoveredThroughMessageId() > 0);
    }

    @Transactional
    public void requestCompaction(ConversationIdentity identity, Long committedThroughMessageId) {
        if (!isEnabled() || identity == null || identity.sessionId() == null
                || identity.userId() == null || committedThroughMessageId == null) {
            return;
        }
        ConversationContextJob job = jobs.findById(identity.sessionId()).orElseGet(() -> {
            ConversationContextJob created = new ConversationContextJob();
            created.setSessionId(identity.sessionId());
            created.setUserId(identity.userId());
            return created;
        });
        if (!identity.userId().equals(job.getUserId())) {
            throw new IllegalStateException("context_job_scope_mismatch");
        }
        long desired = Math.max(
                job.getDesiredThroughMessageId() == null ? 0L : job.getDesiredThroughMessageId(),
                committedThroughMessageId);
        job.setDesiredThroughMessageId(desired);
        if (job.getStatus() == ContextJobStatus.IDLE || job.getStatus() == ContextJobStatus.FAILED) {
            job.setStatus(ContextJobStatus.PENDING);
            job.setAttempts(0);
            job.setLastErrorCode(null);
        }
        job.setNextAttemptAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
    }

    private boolean isEnabled() {
        return "summary".equalsIgnoreCase(properties.getChat().getContextMode());
    }
}
