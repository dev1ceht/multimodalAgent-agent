package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AuditResourceType;
import com.multimodalAgent.agent.domain.DeliveryTask;
import com.multimodalAgent.agent.domain.DeliveryTaskType;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.repository.DeliveryTaskRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.service.audit.AuditLogService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates one durable, auditable notification signal for each overdue active risk case. */
@Service
public class RiskCaseEscalationService {

    private static final Logger log = LoggerFactory.getLogger(RiskCaseEscalationService.class);
    private static final int SCAN_LIMIT = 100;
    private static final List<RiskCaseStatus> ACTIVE_CASE_STATUSES = List.of(
            RiskCaseStatus.OPEN,
            RiskCaseStatus.ACKNOWLEDGED,
            RiskCaseStatus.REFERRED,
            RiskCaseStatus.IN_PROGRESS);

    private final RiskCaseRepository riskCaseRepository;
    private final DeliveryTaskRepository deliveryTaskRepository;
    private final AuditLogService auditLogService;
    private final multimodalAgentProperties properties;
    private final Clock clock;

    public RiskCaseEscalationService(
            RiskCaseRepository riskCaseRepository,
            DeliveryTaskRepository deliveryTaskRepository,
            AuditLogService auditLogService,
            multimodalAgentProperties properties,
            Clock clock
    ) {
        this.riskCaseRepository = riskCaseRepository;
        this.deliveryTaskRepository = deliveryTaskRepository;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${multimodal-agent.risk-case.escalation.poll-interval-ms:60000}")
    @Transactional
    public void pollOverdueCases() {
        scanOverdueCases();
    }

    /**
     * Scans a bounded batch and returns the number of cases that entered the escalation loop.
     * Delivery itself remains asynchronous and is handled by the shared delivery worker.
     */
    @Transactional
    public int scanOverdueCases() {
        Instant now = clock.instant();
        List<RiskCase> candidates = riskCaseRepository
                .findTop100BySlaDueAtBeforeAndOverdueEscalatedAtIsNullAndStatusInOrderBySlaDueAtAsc(
                        now,
                        ACTIVE_CASE_STATUSES,
                        PageRequest.of(0, SCAN_LIMIT));
        List<String> recipients = configuredRecipients();
        if (recipients.isEmpty()) {
            if (!candidates.isEmpty()) {
                log.warn("Overdue risk cases detected but no escalation recipients are configured, count={}",
                        candidates.size());
            }
            return 0;
        }

        int escalatedCases = 0;
        for (RiskCase riskCase : candidates) {
            enqueueEscalation(riskCase, recipients, now);
            riskCase.setOverdueEscalatedAt(now);
            riskCaseRepository.save(riskCase);
            auditLogService.record(
                    null,
                    AuditAction.RISK_CASE_OVERDUE_ESCALATION,
                    AuditResourceType.RISK_CASE,
                    String.valueOf(riskCase.getId()),
                    AuditOutcome.SUCCESS,
                    null,
                    riskCase.getStudentUser() == null ? null : riskCase.getStudentUser().getId(),
                    java.util.Map.of("status", "overdue"));
            escalatedCases++;
        }
        return escalatedCases;
    }

    private void enqueueEscalation(RiskCase riskCase, List<String> recipients, Instant now) {
        for (String recipient : recipients) {
            String idempotencyKey = "risk-case-overdue:" + riskCase.getId() + ":" + recipient;
            if (deliveryTaskRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                continue;
            }
            DeliveryTask task = new DeliveryTask();
            task.setReport(riskCase.getTriggerReport());
            task.setRiskCase(riskCase);
            task.setTaskType(DeliveryTaskType.RISK_CASE_ESCALATION);
            task.setRecipient(recipient);
            task.setIdempotencyKey(idempotencyKey);
            task.setNextAttemptAt(now);
            deliveryTaskRepository.save(task);
        }
    }

    private List<String> configuredRecipients() {
        if (properties.getMcp().getEmail().getRecipients() == null) {
            return List.of();
        }
        Set<String> recipients = properties.getMcp().getEmail().getRecipients().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(recipient -> !recipient.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return List.copyOf(recipients);
    }
}
