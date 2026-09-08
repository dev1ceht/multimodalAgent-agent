package com.multimodalAgent.agent.service.memory;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.LongTermMemoryTask;
import com.multimodalAgent.agent.repository.LongTermMemoryTaskRepository;
import com.multimodalAgent.agent.repository.MemoryFactRepository;
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

@Component
public class LongTermMemoryTaskExecutor {
    private final LongTermMemoryTaskRepository tasks;
    private final MemoryFactRepository facts;
    private final MemoryCompiler compiler;
    private final MemoryPersistenceService persistence;
    private final MemoryVectorStore vectors;
    private final MemoryKeywordStore keywords;
    private final MemoryGraphStore graph;
    private final multimodalAgentProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean draining = new AtomicBoolean();

    public LongTermMemoryTaskExecutor(LongTermMemoryTaskRepository tasks, MemoryFactRepository facts,
            MemoryCompiler compiler, MemoryPersistenceService persistence, MemoryVectorStore vectors,
            MemoryKeywordStore keywords, MemoryGraphStore graph, multimodalAgentProperties properties,
            PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.facts = facts;
        this.compiler = compiler;
        this.persistence = persistence;
        this.vectors = vectors;
        this.keywords = keywords;
        this.graph = graph;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.memory.poll-interval-ms:1000}")
    public void pollDueTasks() {
        if (!properties.getMemory().isEnabled() || !draining.compareAndSet(false, true)) return;
        try {
            Instant now = Instant.now();
            PageRequest page = PageRequest.of(0, Math.max(1, properties.getMemory().getBatchSize()));
            List<LongTermMemoryTask> candidates = new ArrayList<>();
            candidates.addAll(tasks.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                    MemoryTaskStatus.PENDING, now, page));
            candidates.addAll(tasks.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                    MemoryTaskStatus.RETRY_WAIT, now, page));
            candidates.addAll(tasks.findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                    MemoryTaskStatus.PROCESSING, now, page));
            for (LongTermMemoryTask candidate : candidates) {
                Claim claim = claim(candidate.getId());
                if (claim != null) process(claim);
            }
        } finally {
            draining.set(false);
        }
    }

    private Claim claim(String taskId) {
        return transactionTemplate.execute(status -> {
            LongTermMemoryTask task = tasks.findByIdForUpdate(taskId).orElse(null);
            if (task == null || task.getStatus() == MemoryTaskStatus.SUCCEEDED
                    || task.getStatus() == MemoryTaskStatus.FAILED) return null;
            Instant now = Instant.now();
            boolean expired = task.getStatus() == MemoryTaskStatus.PROCESSING
                    && task.getLeaseUntil() != null && !task.getLeaseUntil().isAfter(now);
            boolean due = (task.getStatus() == MemoryTaskStatus.PENDING
                    || task.getStatus() == MemoryTaskStatus.RETRY_WAIT)
                    && !task.getNextAttemptAt().isAfter(now);
            if (!expired && !due) return null;
            String leaseToken = UUID.randomUUID().toString();
            task.setStatus(MemoryTaskStatus.PROCESSING);
            task.setAttempts(task.getAttempts() + 1);
            task.setLeaseToken(leaseToken);
            task.setLeaseUntil(now.plusSeconds(Math.max(1, properties.getMemory().getLeaseSeconds())));
            tasks.saveAndFlush(task);
            return new Claim(task.getId(), leaseToken);
        });
    }

    private void process(Claim claim) {
        try {
            LongTermMemoryTask task = tasks.findById(claim.taskId()).orElseThrow();
            CompiledMemory compiled = null;
            if (task.getCompilationJson() == null || task.getCompilationJson().isBlank()) {
                String existing = String.join("\n", facts.findByUserIdOrderByOccurredAtDesc(
                                task.getUserId(), PageRequest.of(0, 20)).stream()
                        .map(f -> "existing:" + f.getId() + " " + f.getContent()).toList());
                compiled = compiler.compile(new MemoryCompilationInput(
                        task.getUserId(), task.getSessionId(), task.getSourceMessageId(), task.getContent(),
                        existing.isBlank() ? "无" : existing, task.getOccurredAt().toString()));
            }
            renew(claim);
            MemoryProjectionBatch batch = persistence.persist(
                    new MemoryTaskLease(claim.taskId(), claim.leaseToken()), compiled);
            keywords.upsert(batch, () -> renew(claim));
            vectors.upsert(batch, () -> renew(claim));
            graph.upsert(batch, () -> renew(claim));
            complete(claim);
        } catch (Exception exception) {
            fail(claim, exception);
        }
    }

    private void renew(Claim claim) {
        Boolean renewed = transactionTemplate.execute(status -> {
            LongTermMemoryTask task = ownedTask(claim);
            if (task == null) return false;
            task.setLeaseUntil(Instant.now().plusSeconds(
                    Math.max(1, properties.getMemory().getLeaseSeconds())));
            tasks.save(task);
            return true;
        });
        if (!Boolean.TRUE.equals(renewed)) {
            throw new IllegalStateException("Memory task lease was lost during processing.");
        }
    }

    private boolean complete(Claim claim) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LongTermMemoryTask task = ownedTask(claim);
            if (task == null) return false;
            task.setStatus(MemoryTaskStatus.SUCCEEDED);
            task.setLeaseToken(null);
            task.setLeaseUntil(null);
            task.setCompletedAt(Instant.now());
            task.setLastError(null);
            tasks.save(task);
            return true;
        }));
    }

    private MemoryTaskStatus fail(Claim claim, Exception exception) {
        return transactionTemplate.execute(status -> {
            LongTermMemoryTask task = ownedTask(claim);
            if (task == null) return null;
            task.setLastError(abbreviate(exception.getMessage()));
            task.setLeaseToken(null);
            task.setLeaseUntil(null);
            if (task.getAttempts() >= Math.max(1, properties.getMemory().getMaxAttempts())) {
                task.setStatus(MemoryTaskStatus.FAILED);
                task.setCompletedAt(Instant.now());
            } else {
                task.setStatus(MemoryTaskStatus.RETRY_WAIT);
                long base = Math.max(1, properties.getMemory().getBaseRetryDelaySeconds());
                long delay = Math.min(3600, base * (1L << Math.min(8, Math.max(0, task.getAttempts() - 1))));
                task.setNextAttemptAt(Instant.now().plus(delay, ChronoUnit.SECONDS));
            }
            tasks.save(task);
            return task.getStatus();
        });
    }

    private LongTermMemoryTask ownedTask(Claim claim) {
        LongTermMemoryTask task = tasks.findByIdForUpdate(claim.taskId()).orElse(null);
        if (task == null || task.getStatus() != MemoryTaskStatus.PROCESSING) return null;
        return claim.leaseToken().equals(task.getLeaseToken()) ? task : null;
    }

    private String abbreviate(String value) {
        if (value == null) return "unknown error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private record Claim(String taskId, String leaseToken) {}
}
