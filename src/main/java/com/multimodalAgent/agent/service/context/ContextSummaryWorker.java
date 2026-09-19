package com.multimodalAgent.agent.service.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ConversationContextJob;
import com.multimodalAgent.agent.domain.ConversationContextSummary;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ConversationContextJobRepository;
import com.multimodalAgent.agent.repository.ConversationContextSummaryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recoverable worker for per-session rolling context summaries. */
@Component
public class ContextSummaryWorker {

    private final ConversationContextJobRepository jobs;
    private final ConversationContextSummaryRepository summaries;
    private final ChatMessageRepository messages;
    private final ContextSummaryCompiler compiler;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactions;
    private final AtomicBoolean draining = new AtomicBoolean();

    public ContextSummaryWorker(
            ConversationContextJobRepository jobs,
            ConversationContextSummaryRepository summaries,
            ChatMessageRepository messages,
            ContextSummaryCompiler compiler,
            multimodalAgentProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.jobs = jobs;
        this.summaries = summaries;
        this.messages = messages;
        this.compiler = compiler;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.chat.context-summary-poll-interval-ms:1000}")
    public void pollDueJobs() {
        if (!isEnabled() || !draining.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = Instant.now();
            PageRequest page = PageRequest.of(0, Math.max(1, properties.getMemory().getBatchSize()));
            List<ConversationContextJob> candidates = new ArrayList<>();
            candidates.addAll(jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
                    ContextJobStatus.PENDING, now, page));
            candidates.addAll(jobs.findByStatusAndNextAttemptAtLessThanEqualOrderByUpdatedAtAsc(
                    ContextJobStatus.RETRY_WAIT, now, page));
            candidates.addAll(jobs.findByStatusAndLeaseUntilLessThanEqualOrderByUpdatedAtAsc(
                    ContextJobStatus.PROCESSING, now, page));
            for (ConversationContextJob candidate : candidates) {
                Claim claim = claim(candidate.getSessionId());
                if (claim != null) {
                    process(claim);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private Claim claim(Long sessionId) {
        return transactions.execute(status -> {
            ConversationContextJob job = jobs.findBySessionIdForUpdate(sessionId).orElse(null);
            if (job == null || job.getUserId() == null) {
                return null;
            }
            Instant now = Instant.now();
            boolean expired = job.getStatus() == ContextJobStatus.PROCESSING
                    && job.getLeaseUntil() != null && !job.getLeaseUntil().isAfter(now);
            boolean due = (job.getStatus() == ContextJobStatus.PENDING
                    || job.getStatus() == ContextJobStatus.RETRY_WAIT)
                    && !job.getNextAttemptAt().isAfter(now);
            if (!expired && !due) {
                return null;
            }
            ConversationContextSummary summary = summaries.findBySessionIdForUpdate(sessionId).orElse(null);
            long covered = summary == null || summary.getCoveredThroughMessageId() == null
                    ? 0L : summary.getCoveredThroughMessageId();
            int keep = Math.max(2, properties.getChat().getContextSummaryRecentMessages());
            long desired = job.getDesiredThroughMessageId() == null ? 0L : job.getDesiredThroughMessageId();
            int trigger = Math.max(keep, properties.getChat().getContextSummaryTriggerMessages());
            List<ChatMessage> recent = messages
                    .findBySession_IdAndUser_IdAndIdLessThanEqualOrderByIdDesc(
                            sessionId, job.getUserId(), desired, PageRequest.of(0, trigger + 1));
            if (recent.size() <= trigger) {
                markIdle(job);
                return null;
            }
            Long target = recent.get(keep).getId();
            if (target == null || target <= covered) {
                markIdle(job);
                return null;
            }
            String leaseToken = UUID.randomUUID().toString();
            job.setStatus(ContextJobStatus.PROCESSING);
            job.setAttempts(job.getAttempts() + 1);
            job.setLeaseToken(leaseToken);
            job.setLeaseUntil(now.plusSeconds(Math.max(1, properties.getChat().getContextSummaryLeaseSeconds())));
            job.setClaimedBaseVersion(summary == null ? 0L : summary.getVersion());
            job.setClaimedTargetId(target);
            job.setUpdatedAt(now);
            jobs.saveAndFlush(job);
            return new Claim(sessionId, job.getUserId(), leaseToken,
                    job.getClaimedBaseVersion(), covered, target);
        });
    }

    private void process(Claim claim) {
        try {
            ConversationContextSummary summary = summaries.findBySessionId(claim.sessionId()).orElse(null);
            if (summary != null && !claim.userId().equals(summary.getUserId())) {
                throw new IllegalStateException("context_summary_scope_mismatch");
            }
            String previousJson = summary == null ? "{}" : summary.getSummaryJson();
            List<ChatMessage> batch = messages
                    .findBySession_IdAndUser_IdAndIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
                            claim.sessionId(), claim.userId(), claim.coveredThrough(), claim.targetId(),
                            PageRequest.of(0, Math.max(1, properties.getChat().getContextSummaryBatchMaxMessages())));
            if (batch.isEmpty()) {
                finishIdle(claim);
                return;
            }
            ContextSummaryCompiler.CompiledSummary compiled = compiler.compile(previousJson, batch);
            finish(claim, compiled, batch.get(batch.size() - 1).getId());
        } catch (Exception exception) {
            fail(claim, exception);
        }
    }

    private void finish(
            Claim claim,
            ContextSummaryCompiler.CompiledSummary compiled,
            Long newWatermark
    ) {
        transactions.executeWithoutResult(status -> {
            ConversationContextJob job = ownedJob(claim);
            if (job == null) {
                return;
            }
            ConversationContextSummary summary = summaries.findBySessionIdForUpdate(claim.sessionId()).orElse(null);
            long version = summary == null ? 0L : summary.getVersion();
            long covered = summary == null || summary.getCoveredThroughMessageId() == null
                    ? 0L : summary.getCoveredThroughMessageId();
            if (version != claim.baseVersion() || covered != claim.coveredThrough()) {
                clearLease(job);
                job.setStatus(ContextJobStatus.PENDING);
                jobs.save(job);
                return;
            }
            if (summary == null) {
                summary = new ConversationContextSummary();
                summary.setSessionId(claim.sessionId());
                summary.setUserId(claim.userId());
            }
            summary.setVersion(version + 1);
            summary.setCoveredThroughMessageId(newWatermark);
            summary.setSummaryJson(compiled.json());
            summary.setTokenCount(compiled.tokenCount());
            summary.setTokenCounterVersion(ContextTokenEstimator.VERSION);
            summary.setSchemaVersion("conversation-summary-v1");
            summary.setModelKey(modelKey());
            summary.setUpdatedAt(Instant.now());
            summaries.save(summary);
            clearLease(job);
            long desired = job.getDesiredThroughMessageId() == null ? 0L : job.getDesiredThroughMessageId();
            job.setStatus(desired > newWatermark ? ContextJobStatus.PENDING : ContextJobStatus.IDLE);
            job.setLastErrorCode(null);
            jobs.save(job);
        });
    }

    private void finishIdle(Claim claim) {
        transactions.executeWithoutResult(status -> {
            ConversationContextJob job = ownedJob(claim);
            if (job == null) return;
            clearLease(job);
            job.setStatus(ContextJobStatus.IDLE);
            jobs.save(job);
        });
    }

    private void fail(Claim claim, Exception exception) {
        transactions.executeWithoutResult(status -> {
            ConversationContextJob job = ownedJob(claim);
            if (job == null) return;
            clearLease(job);
            job.setLastErrorCode(abbreviate(exception.getMessage()));
            if (job.getAttempts() >= Math.max(1, properties.getChat().getContextSummaryMaxAttempts())) {
                job.setStatus(ContextJobStatus.FAILED);
            } else {
                job.setStatus(ContextJobStatus.RETRY_WAIT);
                long base = Math.max(1, properties.getChat().getContextSummaryBaseRetryDelaySeconds());
                long delay = Math.min(3600, base * (1L << Math.min(8, Math.max(0, job.getAttempts() - 1))));
                job.setNextAttemptAt(Instant.now().plus(delay, ChronoUnit.SECONDS));
            }
            jobs.save(job);
        });
    }

    private ConversationContextJob ownedJob(Claim claim) {
        ConversationContextJob job = jobs.findBySessionIdForUpdate(claim.sessionId()).orElse(null);
        if (job == null || job.getStatus() != ContextJobStatus.PROCESSING
                || !claim.leaseToken().equals(job.getLeaseToken())) {
            return null;
        }
        return job;
    }

    private void clearLease(ConversationContextJob job) {
        job.setLeaseToken(null);
        job.setLeaseUntil(null);
        job.setClaimedBaseVersion(null);
        job.setClaimedTargetId(null);
        job.setUpdatedAt(Instant.now());
    }

    private void markIdle(ConversationContextJob job) {
        job.setStatus(ContextJobStatus.IDLE);
        job.setUpdatedAt(Instant.now());
        jobs.save(job);
    }

    private String modelKey() {
        String provider = properties.getAi().getProvider();
        return provider == null || provider.isBlank() ? "unknown" : provider.trim();
    }

    private boolean isEnabled() {
        return "summary".equalsIgnoreCase(properties.getChat().getContextMode());
    }

    private String abbreviate(String value) {
        if (value == null || value.isBlank()) return "summary_worker_failed";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private record Claim(
            Long sessionId,
            Long userId,
            String leaseToken,
            long baseVersion,
            long coveredThrough,
            Long targetId
    ) {
    }
}
