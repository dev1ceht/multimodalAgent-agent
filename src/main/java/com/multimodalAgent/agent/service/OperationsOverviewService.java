package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.ReferralStatus;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.StudentStatus;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.OperationsCaseStatusCount;
import com.multimodalAgent.agent.dto.OperationsOverviewResponse;
import com.multimodalAgent.agent.dto.OperationsRiskCount;
import com.multimodalAgent.agent.repository.InterventionRecordRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.ReferralRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Builds a bounded, aggregate-only view for school operations planning. */
@Service
public class OperationsOverviewService {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    private static final Duration MAX_WINDOW = Duration.ofDays(365);
    private static final List<ReferralStatus> ACTIVE_REFERRAL_STATUSES =
            List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED);
    private static final List<RiskCaseStatus> ACTIVE_CASE_STATUSES = List.of(
            RiskCaseStatus.OPEN,
            RiskCaseStatus.ACKNOWLEDGED,
            RiskCaseStatus.REFERRED,
            RiskCaseStatus.IN_PROGRESS);

    private final StudentProfileRepository studentProfileRepository;
    private final PsychologicalReportRepository psychologicalReportRepository;
    private final RiskCaseRepository riskCaseRepository;
    private final ReferralRepository referralRepository;
    private final InterventionRecordRepository interventionRecordRepository;
    private final Clock clock;

    public OperationsOverviewService(
            StudentProfileRepository studentProfileRepository,
            PsychologicalReportRepository psychologicalReportRepository,
            RiskCaseRepository riskCaseRepository,
            ReferralRepository referralRepository,
            InterventionRecordRepository interventionRecordRepository,
            Clock clock
    ) {
        this.studentProfileRepository = studentProfileRepository;
        this.psychologicalReportRepository = psychologicalReportRepository;
        this.riskCaseRepository = riskCaseRepository;
        this.referralRepository = referralRepository;
        this.interventionRecordRepository = interventionRecordRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OperationsOverviewResponse overview(CurrentUser viewer, Instant from, Instant to) {
        requireSchoolAdmin(viewer);
        Instant generatedAt = Instant.now(clock);
        OperationsWindow window = resolveWindow(from, to, generatedAt);

        return new OperationsOverviewResponse(
                window.from(),
                window.to(),
                generatedAt,
                studentProfileRepository.countByStatus(StudentStatus.ACTIVE),
                materializeRiskBuckets(
                        psychologicalReportRepository.aggregateStudentReportsByRiskLevel(
                                window.from(), window.to())),
                materializeCaseStatusBuckets(riskCaseRepository.aggregateStudentCasesByStatus()),
                riskCaseRepository.countBySlaDueAtBeforeAndStatusIn(generatedAt, ACTIVE_CASE_STATUSES),
                referralRepository.countByStatusIn(ACTIVE_REFERRAL_STATUSES),
                referralRepository.countByDueAtBeforeAndStatusIn(generatedAt, ACTIVE_REFERRAL_STATUSES),
                interventionRecordRepository.countByOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                        window.from(), window.to()));
    }

    public OperationsWindow normalizeWindow(Instant from, Instant to) {
        return resolveWindow(from, to, Instant.now(clock));
    }

    private List<OperationsRiskCount> materializeRiskBuckets(List<OperationsRiskCount> counts) {
        Map<RiskLevel, Long> byLevel = new EnumMap<>(RiskLevel.class);
        for (OperationsRiskCount count : nonNullRiskCounts(counts)) {
            if (count.riskLevel() != null) {
                byLevel.merge(count.riskLevel(), count.count(), Long::sum);
            }
        }
        return java.util.Arrays.stream(RiskLevel.values())
                .map(level -> new OperationsRiskCount(level, byLevel.getOrDefault(level, 0L)))
                .toList();
    }

    private List<OperationsCaseStatusCount> materializeCaseStatusBuckets(
            List<OperationsCaseStatusCount> counts
    ) {
        Map<RiskCaseStatus, Long> byStatus = new EnumMap<>(RiskCaseStatus.class);
        for (OperationsCaseStatusCount count : nonNullCaseStatusCounts(counts)) {
            if (count.status() != null) {
                byStatus.merge(count.status(), count.count(), Long::sum);
            }
        }
        return java.util.Arrays.stream(RiskCaseStatus.values())
                .map(status -> new OperationsCaseStatusCount(status, byStatus.getOrDefault(status, 0L)))
                .toList();
    }

    private List<OperationsRiskCount> nonNullRiskCounts(List<OperationsRiskCount> counts) {
        return counts == null ? List.of() : counts;
    }

    private List<OperationsCaseStatusCount> nonNullCaseStatusCounts(
            List<OperationsCaseStatusCount> counts
    ) {
        return counts == null ? List.of() : counts;
    }

    private OperationsWindow resolveWindow(Instant from, Instant to, Instant now) {
        if (from == null && to == null) {
            return new OperationsWindow(now.minus(DEFAULT_WINDOW), now);
        }
        if (from == null || to == null) {
            throw badRequest("Operations overview requires both from and to");
        }
        if (!from.isBefore(to)) {
            throw badRequest("Operations overview requires from before to");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw badRequest("Operations overview window cannot exceed 365 days");
        }
        if (from.isBefore(now.minus(MAX_WINDOW)) || to.isAfter(now)) {
            throw badRequest("Operations overview window must be within the last 365 days");
        }
        return new OperationsWindow(from, to);
    }

    private void requireSchoolAdmin(CurrentUser viewer) {
        if (viewer == null || viewer.getAuthorities() == null
                || viewer.getAuthorities().stream().noneMatch(authority ->
                UserRole.SCHOOL_ADMIN.authority().equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operations overview access denied");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
