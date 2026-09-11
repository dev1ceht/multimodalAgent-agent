package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.KnowledgeEventType;
import com.multimodalAgent.agent.domain.KnowledgeInboxEvent;
import com.multimodalAgent.agent.domain.KnowledgeInboxStatus;
import com.multimodalAgent.agent.repository.KnowledgeInboxEventRepository;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims durable inbox work and hands long-running parsing/indexing to a bounded executor. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class KnowledgeInboxWorker {

    private final KnowledgeInboxEventRepository inboxRepository;
    private final KnowledgeUploadService uploadService;
    private final KnowledgeObjectStore objectStore;
    private final KnowledgeTextExtractor textExtractor;
    private final KnowledgeService knowledgeService;
    private final KnowledgeIndexTaskExecutor indexExecutor;
    private final multimodalAgentProperties properties;
    private final ThreadPoolTaskExecutor workerExecutor;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeInboxWorker(
            KnowledgeInboxEventRepository inboxRepository,
            KnowledgeUploadService uploadService,
            KnowledgeObjectStore objectStore,
            multimodalAgentProperties properties,
            KnowledgeService knowledgeService,
            KnowledgeIndexTaskExecutor indexExecutor,
            ThreadPoolTaskExecutor workerExecutor,
            PlatformTransactionManager transactionManager) {
        this.inboxRepository = inboxRepository;
        this.uploadService = uploadService;
        this.objectStore = objectStore;
        this.textExtractor = new KnowledgeTextExtractor(properties);
        this.properties = properties;
        this.knowledgeService = knowledgeService;
        this.indexExecutor = indexExecutor;
        this.workerExecutor = workerExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.knowledge.kafka.poll-interval-ms:1000}")
    public void dispatchInboxWork() {
        Instant now = Instant.now();
        int batchSize = Math.max(1, properties.getKnowledge().getKafka().getBatchSize());
        List<KnowledgeInboxEvent> candidates = new ArrayList<>();
        PageRequest page = PageRequest.of(0, batchSize);
        candidates.addAll(inboxRepository.findByStatusOrderByCreatedAtAsc(KnowledgeInboxStatus.QUEUED, page));
        candidates.addAll(inboxRepository.findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAtAsc(
                KnowledgeInboxStatus.RUNNING, now, page));
        for (KnowledgeInboxEvent candidate : candidates) {
            Claim claim = claim(candidate.getEventId());
            if (claim == null) continue;
            try {
                workerExecutor.execute(() -> process(claim));
            } catch (RejectedExecutionException exception) {
                release(claim);
            }
        }
    }

    private Claim claim(String eventId) {
        return transactionTemplate.execute(status -> {
            KnowledgeInboxEvent event = inboxRepository.findById(eventId).orElse(null);
            if (event == null || event.getStatus() == KnowledgeInboxStatus.DONE
                    || event.getStatus() == KnowledgeInboxStatus.OBSOLETE) return null;
            Instant now = Instant.now();
            if (event.getStatus() == KnowledgeInboxStatus.RUNNING
                    && event.getLeaseUntil() != null && event.getLeaseUntil().isAfter(now)) return null;
            String token = UUID.randomUUID().toString();
            event.claim(token, now.plusSeconds(Math.max(1,
                    properties.getKnowledge().getKafka().getLeaseSeconds())));
            inboxRepository.saveAndFlush(event);
            return new Claim(event.getEventId(), event.getEventType(), event.getAggregateId(),
                    event.getDispatchGeneration(), token);
        });
    }

    private void process(Claim claim) {
        if (claim.type() == KnowledgeEventType.KNOWLEDGE_PARSE_REQUESTED) {
            processParse(claim);
        } else if (claim.type() == KnowledgeEventType.KNOWLEDGE_INDEX_REQUESTED) {
            processIndex(claim);
        } else {
            complete(claim, true);
        }
    }

    private void processParse(Claim claim) {
        KnowledgeUploadService.ClaimedUpload upload = uploadService
                .claimForParsing(claim.aggregateId(), claim.generation());
        if (upload == null) {
            complete(claim, true);
            return;
        }
        try (KnowledgeObjectStore.StoredObject stored = objectStore.get(upload.bucket(), upload.objectKey())) {
            if (stored.ref().size() != -1 && stored.ref().size() != uploadSize(upload)) {
                throw new KnowledgeParseException("OBJECT_SIZE_MISMATCH", "Stored object size does not match upload", false);
            }
            DigestInputStream digest = new DigestInputStream(stored.content(), MessageDigest.getInstance("SHA-256"));
            String text = textExtractor.extract(upload.filename(), digest);
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!upload.sha256().equalsIgnoreCase(actualHash)) {
                throw new KnowledgeParseException("OBJECT_HASH_MISMATCH", "Stored object hash does not match upload", false);
            }
            KnowledgeService.ParsedUploadResult result = knowledgeService.ingestParsedUpload(
                    upload.uploadId(), upload.dispatchGeneration(), upload.leaseToken(), text,
                    KnowledgeTextExtractor.PARSER_VERSION);
            complete(claim, "OBSOLETE".equals(result.status()));
        } catch (KnowledgeParseException exception) {
            uploadService.markParseFailure(upload.uploadId(), upload.dispatchGeneration(), upload.leaseToken(),
                    exception.getCode(), exception.getMessage(), exception.isRetryable(), "parse:" + claim.eventId());
            complete(claim, false);
        } catch (Exception exception) {
            uploadService.markParseFailure(upload.uploadId(), upload.dispatchGeneration(), upload.leaseToken(),
                    "PARSER_RUNTIME_FAILED", exception.getMessage(), true, "parse:" + claim.eventId());
            complete(claim, false);
        }
    }

    private long uploadSize(KnowledgeUploadService.ClaimedUpload upload) {
        // The size is checked against the upload row by the object-store service before parsing;
        // this method keeps the worker independent of the public response DTO.
        return uploadService.get(upload.uploadId()).getSizeBytes();
    }

    private void processIndex(Claim claim) {
        try {
            boolean accepted = indexExecutor.executeKafkaTask(Long.valueOf(claim.aggregateId()), claim.generation());
            complete(claim, !accepted);
        } catch (RuntimeException exception) {
            complete(claim, false);
        }
    }

    private void complete(Claim claim, boolean obsolete) {
        transactionTemplate.executeWithoutResult(status -> {
            KnowledgeInboxEvent event = inboxRepository.findById(claim.eventId()).orElse(null);
            if (event != null && claim.leaseToken().equals(event.getLeaseToken())
                    && event.getStatus() == KnowledgeInboxStatus.RUNNING) {
                event.markDone(obsolete);
                inboxRepository.save(event);
            }
        });
    }

    private void release(Claim claim) {
        transactionTemplate.executeWithoutResult(status -> {
            inboxRepository.findById(claim.eventId()).ifPresent(event -> {
                if (claim.leaseToken().equals(event.getLeaseToken())) {
                    event.setStatus(KnowledgeInboxStatus.QUEUED);
                    event.setLeaseToken(null);
                    event.setLeaseUntil(null);
                    inboxRepository.save(event);
                }
            });
        });
    }

    private record Claim(
            String eventId,
            KnowledgeEventType type,
            String aggregateId,
            long generation,
            String leaseToken) {
    }

    private static final class DigestInputStream extends FilterInputStream {
        private final MessageDigest digest;

        private DigestInputStream(InputStream input, MessageDigest digest) {
            super(input);
            this.digest = digest;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) digest.update((byte) value);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) digest.update(bytes, offset, count);
            return count;
        }
    }
}
