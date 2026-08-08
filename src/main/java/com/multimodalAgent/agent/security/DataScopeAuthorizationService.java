package com.multimodalAgent.agent.security;

import com.multimodalAgent.agent.domain.AlertRecord;
import com.multimodalAgent.agent.domain.CounselorAssignment;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.CounselorAssignmentRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class DataScopeAuthorizationService {

    private final StudentProfileRepository studentProfileRepository;
    private final CounselorAssignmentRepository counselorAssignmentRepository;

    public DataScopeAuthorizationService(
            StudentProfileRepository studentProfileRepository,
            CounselorAssignmentRepository counselorAssignmentRepository
    ) {
        this.studentProfileRepository = studentProfileRepository;
        this.counselorAssignmentRepository = counselorAssignmentRepository;
    }

    public boolean canViewReport(CurrentUser viewer, PsychologicalReport report) {
        if (viewer == null || report == null || report.getUser() == null) {
            return false;
        }
        if (hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)) {
            return report.getRiskLevel() == RiskLevel.HIGH
                    && UserRole.isStudentAccount(report.getUser().getRoles());
        }
        if (hasRole(viewer, UserRole.STUDENT) && sameUser(viewer, report.getUser())) {
            return true;
        }
        if (!hasRole(viewer, UserRole.COUNSELOR)
                || viewer.getId() == null
                || report.getUser().getId() == null) {
            return false;
        }
        return studentProfileRepository.findByUser_Id(report.getUser().getId())
                .map(profile -> hasCoveredAssignment(viewer.getId(), profile))
                .orElse(false);
    }

    public List<PsychologicalReport> filterReports(
            CurrentUser viewer,
            Collection<PsychologicalReport> reports
    ) {
        if (viewer == null || reports == null || reports.isEmpty()) {
            return List.of();
        }
        List<PsychologicalReport> candidates = reports.stream()
                .filter(report -> report != null && report.getUser() != null)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)) {
            return candidates.stream()
                    .filter(report -> report.getRiskLevel() == RiskLevel.HIGH
                            && UserRole.isStudentAccount(report.getUser().getRoles()))
                    .toList();
        }
        if (!hasRole(viewer, UserRole.COUNSELOR) || viewer.getId() == null) {
            return candidates.stream()
                    .filter(report -> hasRole(viewer, UserRole.STUDENT)
                            && sameUser(viewer, report.getUser()))
                    .toList();
        }

        Set<Long> ownerIds = candidates.stream()
                .map(report -> report.getUser().getId())
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, StudentProfile> profilesByUserId = ownerIds.isEmpty()
                ? Map.of()
                : studentProfileRepository.findAllByUser_IdIn(ownerIds)
                        .stream()
                        .filter(profile -> profile.getUser() != null && profile.getUser().getId() != null)
                        .collect(Collectors.toMap(
                                profile -> profile.getUser().getId(),
                                Function.identity(),
                                (first, ignored) -> first));
        List<CounselorAssignment> assignments = counselorAssignmentRepository
                .findByCounselor_IdAndEnabledTrue(viewer.getId());
        return candidates.stream()
                .filter(report -> sameUser(viewer, report.getUser())
                        || (profilesByUserId.containsKey(report.getUser().getId())
                        && assignmentsCoverProfile(
                        assignments,
                        profilesByUserId.get(report.getUser().getId()))))
                .toList();
    }

    public List<AlertRecord> filterAlerts(CurrentUser viewer, Collection<AlertRecord> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        List<AlertRecord> candidates = alerts.stream()
                .filter(alert -> alert != null && alert.getReport() != null)
                .toList();
        Set<PsychologicalReport> visibleReports = Set.copyOf(filterReports(
                viewer,
                candidates.stream().map(AlertRecord::getReport).toList()));
        return candidates.stream()
                .filter(alert -> visibleReports.contains(alert.getReport()))
                .toList();
    }

    public boolean canViewUser(CurrentUser viewer, UserAccount subject) {
        if (viewer == null || subject == null) {
            return false;
        }
        if (hasRole(viewer, UserRole.STUDENT) && sameUser(viewer, subject)) {
            return true;
        }
        if (!hasRole(viewer, UserRole.COUNSELOR) || viewer.getId() == null || subject.getId() == null) {
            return false;
        }
        return studentProfileRepository.findByUser_Id(subject.getId())
                .map(profile -> hasCoveredAssignment(viewer.getId(), profile))
                .orElse(false);
    }

    public Set<Long> visibleStudentUserIds(CurrentUser viewer) {
        if (viewer == null || viewer.getId() == null) {
            return Set.of();
        }
        List<StudentProfile> profiles = studentProfileRepository.findAllWithOrganization();
        if (hasRole(viewer, UserRole.PSYCHOLOGY_CENTER)) {
            return profileUserIds(profiles);
        }
        if (hasRole(viewer, UserRole.COUNSELOR)) {
            List<CounselorAssignment> assignments = counselorAssignmentRepository
                    .findByCounselor_IdAndEnabledTrue(viewer.getId());
            return profiles.stream()
                    .filter(profile -> assignmentsCoverProfile(assignments, profile))
                    .map(StudentProfile::getUser)
                    .filter(user -> user != null && user.getId() != null)
                    .map(UserAccount::getId)
                    .collect(Collectors.toSet());
        }
        if (hasRole(viewer, UserRole.STUDENT)) {
            return Set.of(viewer.getId());
        }
        return Set.of();
    }

    public boolean isPsychologyCenter(CurrentUser viewer) {
        return viewer != null && hasRole(viewer, UserRole.PSYCHOLOGY_CENTER);
    }

    private Set<Long> profileUserIds(Collection<StudentProfile> profiles) {
        return profiles.stream()
                .map(StudentProfile::getUser)
                .filter(user -> user != null && user.getId() != null)
                .map(UserAccount::getId)
                .collect(Collectors.toSet());
    }

    private boolean hasCoveredAssignment(Long counselorId, StudentProfile profile) {
        return assignmentsCoverProfile(
                counselorAssignmentRepository.findByCounselor_IdAndEnabledTrue(counselorId),
                profile);
    }

    private boolean assignmentsCoverProfile(
            Collection<CounselorAssignment> assignments,
            StudentProfile profile
    ) {
        return assignments != null && assignments.stream().anyMatch(assignment -> assignment.covers(profile));
    }

    private boolean sameUser(CurrentUser viewer, UserAccount subject) {
        return viewer.getId() != null && viewer.getId().equals(subject.getId());
    }

    private boolean hasRole(CurrentUser viewer, UserRole role) {
        Collection<? extends GrantedAuthority> authorities = viewer.getAuthorities();
        return authorities != null && authorities.stream()
                .anyMatch(authority -> role.authority().equals(authority.getAuthority()));
    }
}
