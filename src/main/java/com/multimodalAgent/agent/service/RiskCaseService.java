package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.RiskCaseSlaProperties;
import com.multimodalAgent.agent.domain.InterventionRecord;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.Referral;
import com.multimodalAgent.agent.domain.ReferralStatus;
import com.multimodalAgent.agent.domain.ReferralTargetType;
import com.multimodalAgent.agent.domain.RiskCase;
import com.multimodalAgent.agent.domain.RiskCaseSource;
import com.multimodalAgent.agent.domain.RiskCaseStatus;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.dto.InterventionCreateRequest;
import com.multimodalAgent.agent.dto.ReferralCreateRequest;
import com.multimodalAgent.agent.dto.StudentSupportStatusResponse;
import com.multimodalAgent.agent.repository.InterventionRecordRepository;
import com.multimodalAgent.agent.repository.ReferralRepository;
import com.multimodalAgent.agent.repository.RiskCaseRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.security.DataScopeAuthorizationService;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskCaseService {

    private static final String AUTOMATED_OPENING_REASON =
            "High-risk assessment requires human follow-up.";

    private final RiskCaseRepository riskCaseRepository;
    private final ReferralRepository referralRepository;
    private final InterventionRecordRepository interventionRecordRepository;
    private final UserAccountRepository userAccountRepository;
    private final DataScopeAuthorizationService dataScopeAuthorizationService;
    private final Clock clock;
    private final RiskCaseSlaProperties slaProperties;

    public RiskCaseService(
            RiskCaseRepository riskCaseRepository,
            ReferralRepository referralRepository,
            InterventionRecordRepository interventionRecordRepository,
            UserAccountRepository userAccountRepository,
            DataScopeAuthorizationService dataScopeAuthorizationService,
            Clock clock,
            RiskCaseSlaProperties slaProperties
    ) {
        this.riskCaseRepository = riskCaseRepository;
        this.referralRepository = referralRepository;
        this.interventionRecordRepository = interventionRecordRepository;
        this.userAccountRepository = userAccountRepository;
        this.dataScopeAuthorizationService = dataScopeAuthorizationService;
        this.clock = clock;
        this.slaProperties = slaProperties;
    }

    /** Opens the human follow-up case once, after the report has been persisted. */
    @Transactional
    public Optional<RiskCase> ensureCaseForReport(PsychologicalReport report) {
        if (report == null
                || report.getId() == null
                || !slaProperties.opensCase(report.getRiskLevel())
                || report.getUser() == null
                || !UserRole.isStudentAccount(report.getUser().getRoles())) {
            return Optional.empty();
        }
        return riskCaseRepository.findByTriggerReport_Id(report.getId()).or(() -> {
            RiskCase riskCase = new RiskCase();
            riskCase.setTriggerReport(report);
            riskCase.setStudentUser(report.getUser());
            riskCase.setRiskLevel(report.getRiskLevel());
            riskCase.setSource(RiskCaseSource.AUTOMATED_ASSESSMENT);
            riskCase.setOpeningReason(AUTOMATED_OPENING_REASON);
            riskCase.setSlaDueAt(clock.instant().plus(slaProperties.getHighRiskCaseResponse()));
            return Optional.of(riskCaseRepository.save(riskCase));
        });
    }

    @Transactional(readOnly = true)
    public List<RiskCase> staffCases(CurrentUser viewer) {
        requireStaffReviewer(viewer);
        List<RiskCase> candidates;
        if (hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)) {
            candidates = riskCaseRepository.findTop100ByRiskLevelOrderByUpdatedAtDesc(RiskLevel.HIGH);
        } else {
            Set<Long> studentIds = dataScopeAuthorizationService.visibleStudentUserIds(viewer);
            candidates = studentIds.isEmpty()
                    ? List.of()
                    : riskCaseRepository.findTop100ByStudentUser_IdInAndRiskLevelOrderByUpdatedAtDesc(
                            studentIds,
                            RiskLevel.HIGH);
        }
        return candidates.stream().filter(riskCase -> canManage(viewer, riskCase)).toList();
    }

    @Transactional(readOnly = true)
    public RiskCase staffCase(CurrentUser viewer, Long caseId) {
        RiskCase riskCase = findCase(caseId);
        requireManage(viewer, riskCase);
        return riskCase;
    }

    @Transactional
    public RiskCase transitionCase(CurrentUser viewer, Long caseId, RiskCaseStatus targetStatus) {
        return transitionCase(viewer, caseId, targetStatus, null);
    }

    @Transactional
    public RiskCase transitionCase(
            CurrentUser viewer,
            Long caseId,
            RiskCaseStatus targetStatus,
            Long expectedVersion
    ) {
        RiskCase riskCase = staffCase(viewer, caseId);
        requireExpectedVersion(riskCase.getVersion(), expectedVersion, "Risk case");
        transitionCaseOrConflict(riskCase, targetStatus);
        return riskCaseRepository.saveAndFlush(riskCase);
    }

    @Transactional(readOnly = true)
    public List<Referral> referrals(CurrentUser viewer, Long caseId) {
        RiskCase riskCase = staffCase(viewer, caseId);
        return referralRepository.findByRiskCase_IdOrderByCreatedAtDesc(riskCase.getId());
    }

    @Transactional
    public Referral createReferral(CurrentUser viewer, Long caseId, ReferralCreateRequest request) {
        RiskCase riskCase = staffCase(viewer, caseId);
        if (riskCase.getStatus() == RiskCaseStatus.CLOSED) {
            throw conflict("Closed risk cases cannot receive new referrals");
        }
        UserAccount targetUser = referralTarget(request);
        UserAccount actor = actor(viewer);

        Referral referral = new Referral();
        referral.setRiskCase(riskCase);
        referral.setCreatedBy(actor);
        referral.setTargetUser(targetUser);
        referral.setTargetType(request.targetType());
        referral.setReason(request.reason().trim());
        referral.setDueAt(request.dueAt() == null
                ? clock.instant().plus(slaProperties.getReferralResponse())
                : request.dueAt());

        if (riskCase.getStatus() == RiskCaseStatus.OPEN
                || riskCase.getStatus() == RiskCaseStatus.ACKNOWLEDGED
                || riskCase.getStatus() == RiskCaseStatus.IN_PROGRESS
                || riskCase.getStatus() == RiskCaseStatus.RESOLVED) {
            transitionCaseOrConflict(riskCase, RiskCaseStatus.REFERRED);
            riskCaseRepository.saveAndFlush(riskCase);
        }
        return referralRepository.saveAndFlush(referral);
    }

    @Transactional
    public Referral transitionReferral(
            CurrentUser viewer,
            Long caseId,
            Long referralId,
            ReferralStatus targetStatus
    ) {
        return transitionReferral(viewer, caseId, referralId, targetStatus, null);
    }

    @Transactional
    public Referral transitionReferral(
            CurrentUser viewer,
            Long caseId,
            Long referralId,
            ReferralStatus targetStatus,
            Long expectedVersion
    ) {
        RiskCase riskCase = staffCase(viewer, caseId);
        Referral referral = referralRepository.findByIdAndRiskCase_Id(referralId, caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Referral not found"));
        requireExpectedVersion(referral.getVersion(), expectedVersion, "Referral");
        try {
            referral.transitionTo(targetStatus);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
        if (targetStatus == ReferralStatus.ACCEPTED
                && riskCase.getStatus() == RiskCaseStatus.REFERRED) {
            transitionCaseOrConflict(riskCase, RiskCaseStatus.IN_PROGRESS);
            riskCaseRepository.saveAndFlush(riskCase);
        }
        return referralRepository.saveAndFlush(referral);
    }

    @Transactional(readOnly = true)
    public List<InterventionRecord> interventions(CurrentUser viewer, Long caseId) {
        RiskCase riskCase = staffCase(viewer, caseId);
        return interventionRecordRepository.findByRiskCase_IdOrderByOccurredAtDesc(riskCase.getId());
    }

    @Transactional
    public InterventionRecord recordIntervention(
            CurrentUser viewer,
            Long caseId,
            InterventionCreateRequest request
    ) {
        RiskCase riskCase = staffCase(viewer, caseId);
        if (riskCase.getStatus() == RiskCaseStatus.CLOSED) {
            throw conflict("Closed risk cases cannot receive interventions");
        }
        if (riskCase.getStatus() != RiskCaseStatus.IN_PROGRESS) {
            transitionCaseOrConflict(riskCase, RiskCaseStatus.IN_PROGRESS);
            riskCaseRepository.saveAndFlush(riskCase);
        }

        InterventionRecord record = new InterventionRecord();
        record.setRiskCase(riskCase);
        record.setCreatedBy(actor(viewer));
        record.setType(request.type());
        record.setNotes(request.notes().trim());
        record.setOutcome(request.outcome() == null ? null : request.outcome().trim());
        record.setOccurredAt(request.occurredAt());
        record.setFollowUpAt(request.followUpAt());
        return interventionRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public List<StudentSupportStatusResponse> studentSupportStatus(CurrentUser viewer) {
        requireStudent(viewer);
        List<RiskCase> cases = riskCaseRepository.findTop100ByStudentUser_IdOrderByUpdatedAtDesc(viewer.getId());
        List<Referral> activeReferrals = referralRepository.findByRiskCase_StudentUser_IdAndStatusIn(
                viewer.getId(),
                List.of(ReferralStatus.PENDING, ReferralStatus.ACCEPTED));
        Set<Long> activeCaseIds = activeReferrals.stream()
                .map(Referral::getRiskCase)
                .filter(java.util.Objects::nonNull)
                .map(RiskCase::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return cases.stream()
                .map(riskCase -> new StudentSupportStatusResponse(
                        riskCase.getId(),
                        riskCase.getStatus(),
                        activeCaseIds.contains(riskCase.getId()),
                        riskCase.getUpdatedAt()))
                .toList();
    }

    private UserAccount referralTarget(ReferralCreateRequest request) {
        boolean needsUser = request.targetType() == ReferralTargetType.COUNSELOR;
        if (needsUser && request.targetUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Counselor referrals require a target user");
        }
        if (!needsUser && request.targetUserId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only counselor referrals accept a target user");
        }
        if (!needsUser) {
            return null;
        }
        UserAccount target = userAccountRepository.findById(request.targetUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Referral target not found"));
        if (target.getRoles() == null || !target.getRoles().contains(UserRole.COUNSELOR.authority())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Referral target must be a counselor");
        }
        return target;
    }

    private RiskCase findCase(Long caseId) {
        if (caseId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk case not found");
        }
        return riskCaseRepository.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Risk case not found"));
    }

    private void requireManage(CurrentUser viewer, RiskCase riskCase) {
        if (!canManage(viewer, riskCase)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Risk case access denied");
        }
    }

    private boolean canManage(CurrentUser viewer, RiskCase riskCase) {
        if (viewer == null || riskCase == null || riskCase.getStudentUser() == null) {
            return false;
        }
        if (hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)) {
            return riskCase.getRiskLevel() == RiskLevel.HIGH
                    && UserRole.isStudentAccount(riskCase.getStudentUser().getRoles());
        }
        return hasRole(viewer, UserRole.COUNSELOR)
                && dataScopeAuthorizationService.canViewUser(viewer, riskCase.getStudentUser());
    }

    private void requireStaffReviewer(CurrentUser viewer) {
        if (viewer == null || (!hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)
                && !hasRole(viewer, UserRole.COUNSELOR))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Risk case access denied");
        }
    }

    private void requireStudent(CurrentUser viewer) {
        if (viewer == null || viewer.getId() == null || viewer.getAuthorities() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student support status access denied");
        }
        Set<String> roles = viewer.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        if (!UserRole.isStudentAccount(roles)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Student support status access denied");
        }
    }

    private UserAccount actor(CurrentUser viewer) {
        if (viewer == null || viewer.getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated staff account required");
        }
        return userAccountRepository.findById(viewer.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff account not found"));
    }

    private void transitionCaseOrConflict(RiskCase riskCase, RiskCaseStatus targetStatus) {
        try {
            riskCase.transitionTo(targetStatus);
        } catch (IllegalStateException exception) {
            throw conflict(exception.getMessage());
        }
    }

    private void requireExpectedVersion(long currentVersion, Long expectedVersion, String resourceName) {
        if (expectedVersion != null && expectedVersion != currentVersion) {
            throw conflict(resourceName + " was updated by another request; reload before retrying");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private boolean hasRole(CurrentUser viewer, UserRole role) {
        Collection<? extends GrantedAuthority> authorities = viewer == null ? null : viewer.getAuthorities();
        return authorities != null && authorities.stream()
                .anyMatch(authority -> role.authority().equals(authority.getAuthority()));
    }
}
