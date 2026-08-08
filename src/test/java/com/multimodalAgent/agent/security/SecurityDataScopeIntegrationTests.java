package com.multimodalAgent.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.AssignmentScopeType;
import com.multimodalAgent.agent.domain.ClassGroup;
import com.multimodalAgent.agent.domain.CounselorAssignment;
import com.multimodalAgent.agent.domain.Department;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.Major;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.StudentProfile;
import com.multimodalAgent.agent.domain.StudentStatus;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.domain.UserRole;
import com.multimodalAgent.agent.repository.AuditLogRepository;
import com.multimodalAgent.agent.repository.ClassGroupRepository;
import com.multimodalAgent.agent.repository.ConsentRecordRepository;
import com.multimodalAgent.agent.repository.CounselorAssignmentRepository;
import com.multimodalAgent.agent.repository.DepartmentRepository;
import com.multimodalAgent.agent.repository.MajorRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.StudentProfileRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:multimodalAgent-security-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false",
                "multimodal-agent.security.demo-accounts-enabled=true"
        })
@AutoConfigureWebTestClient
class SecurityDataScopeIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PsychologicalReportRepository psychologicalReportRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private MajorRepository majorRepository;

    @Autowired
    private ClassGroupRepository classGroupRepository;

    @Autowired
    private StudentProfileRepository studentProfileRepository;

    @Autowired
    private CounselorAssignmentRepository counselorAssignmentRepository;

    @Autowired
    private ConsentRecordRepository consentRecordRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUpReportsForDifferentUsers() {
        counselorAssignmentRepository.deleteAll();
        consentRecordRepository.deleteAll();
        studentProfileRepository.deleteAll();
        classGroupRepository.deleteAll();
        majorRepository.deleteAll();
        departmentRepository.deleteAll();
        psychologicalReportRepository.deleteAll();
        userAccountRepository.findByUsername("system-admin").ifPresent(userAccountRepository::delete);
        userAccountRepository.findByUsername("center-reviewer").ifPresent(userAccountRepository::delete);
        userAccountRepository.findByUsername("other-student").ifPresent(userAccountRepository::delete);

        UserAccount student = userAccountRepository.findByUsername("student").orElseThrow();
        UserAccount admin = userAccountRepository.findByUsername("admin").orElseThrow();

        Department department = new Department();
        department.setCode("TEST-DEPT");
        department.setName("Test Department");
        department = departmentRepository.save(department);

        Major major = new Major();
        major.setDepartment(department);
        major.setCode("TEST-MAJOR");
        major.setName("Test Major");
        major = majorRepository.save(major);

        ClassGroup classGroup = new ClassGroup();
        classGroup.setMajor(major);
        classGroup.setCode("TEST-CLASS");
        classGroup.setName("Test Class");
        classGroup.setGradeYear(2025);
        classGroup = classGroupRepository.save(classGroup);

        StudentProfile profile = new StudentProfile();
        profile.setUser(student);
        profile.setStudentNo("TEST-0001");
        profile.setDepartment(department);
        profile.setMajor(major);
        profile.setStudentClass(classGroup);
        profile.setGradeYear(2025);
        profile.setStatus(StudentStatus.ACTIVE);
        studentProfileRepository.save(profile);

        CounselorAssignment assignment = new CounselorAssignment();
        assignment.setCounselor(admin);
        assignment.setScopeType(AssignmentScopeType.CLASS);
        assignment.setStudentClass(classGroup);
        counselorAssignmentRepository.save(assignment);

        Department otherDepartment = new Department();
        otherDepartment.setCode("OTHER-DEPT");
        otherDepartment.setName("Other Department");
        otherDepartment = departmentRepository.save(otherDepartment);

        Major otherMajor = new Major();
        otherMajor.setDepartment(otherDepartment);
        otherMajor.setCode("OTHER-MAJOR");
        otherMajor.setName("Other Major");
        otherMajor = majorRepository.save(otherMajor);

        ClassGroup otherClass = new ClassGroup();
        otherClass.setMajor(otherMajor);
        otherClass.setCode("OTHER-CLASS");
        otherClass.setName("Other Class");
        otherClass.setGradeYear(2025);
        otherClass = classGroupRepository.save(otherClass);

        psychologicalReportRepository.save(reportFor(student, "student-only-report"));
        psychologicalReportRepository.save(reportFor(admin, "admin-owned-report"));

        UserAccount otherStudent = new UserAccount();
        otherStudent.setUsername("other-student");
        otherStudent.setDisplayName("Other Student");
        otherStudent.setPassword(passwordEncoder.encode("other123"));
        otherStudent.setRoles(Set.of(UserRole.STUDENT.authority()));
        otherStudent = userAccountRepository.save(otherStudent);
        StudentProfile otherProfile = new StudentProfile();
        otherProfile.setUser(otherStudent);
        otherProfile.setStudentNo("TEST-0002");
        otherProfile.setDepartment(otherDepartment);
        otherProfile.setMajor(otherMajor);
        otherProfile.setStudentClass(otherClass);
        otherProfile.setGradeYear(2025);
        otherProfile.setStatus(StudentStatus.ACTIVE);
        studentProfileRepository.save(otherProfile);
        psychologicalReportRepository.save(reportFor(
                otherStudent,
                "high-risk-report",
                RiskLevel.HIGH));

        UserAccount centerReviewer = new UserAccount();
        centerReviewer.setUsername("center-reviewer");
        centerReviewer.setDisplayName("Center Reviewer");
        centerReviewer.setPassword(passwordEncoder.encode("center123"));
        centerReviewer.setRoles(Set.of(UserRole.PSYCHOLOGY_CENTER.authority()));
        centerReviewer = userAccountRepository.save(centerReviewer);
        psychologicalReportRepository.save(reportFor(
                centerReviewer,
                "center-owned-report",
                RiskLevel.HIGH));

        UserAccount systemAdmin = new UserAccount();
        systemAdmin.setUsername("system-admin");
        systemAdmin.setDisplayName("System Admin");
        systemAdmin.setPassword(passwordEncoder.encode("system123"));
        systemAdmin.setRoles(Set.of(UserRole.SYSTEM_ADMIN.authority()));
        userAccountRepository.save(systemAdmin);
    }

    @Test
    void studentCanReadOnlyTheirOwnReportCollection() {
        webTestClient.get()
                .uri("/api/reports/me")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].username").isEqualTo("student");
    }

    @Test
    void studentCannotReadAdminReportsAndDeniedAttemptIsAudited() {
        webTestClient.get()
                .uri("/api/admin/reports")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(log -> log.getActorUsername().equals("student")
                        && log.getAction() == AuditAction.ACCESS_DENIED
                        && log.getOutcome() == AuditOutcome.DENIED);
    }

    @Test
    void adminCanReadAdminReportCollection() {
        webTestClient.get()
                .uri("/api/admin/reports")
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].username").isEqualTo("student");
    }

    @Test
    void psychologyCenterCanReadHighRiskReports() {
        webTestClient.get()
                .uri("/api/admin/reports")
                .headers(headers -> headers.setBasicAuth("center-reviewer", "center123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].username").isEqualTo("other-student")
                .jsonPath("$[0].riskLevel").isEqualTo("HIGH");
    }

    @Test
    void systemAdminCannotReadSensitiveReportsWithoutCounselorRole() {
        webTestClient.get()
                .uri("/api/admin/reports")
                .headers(headers -> headers.setBasicAuth("system-admin", "system123"))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(log -> log.getActorUsername().equals("system-admin")
                        && log.getAction() == AuditAction.ACCESS_DENIED
                        && log.getOutcome() == AuditOutcome.DENIED);
    }

    @Test
    void studentCanReadAndUpdateTheirOwnProfileWithMaskedPhone() {
        webTestClient.get()
                .uri("/api/student/profile")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.studentNo").isEqualTo("TEST-0001")
                .jsonPath("$.departmentCode").isEqualTo("TEST-DEPT");

        webTestClient.put()
                .uri("/api/student/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue("{\"gender\":\"F\",\"phone\":\"13800138000\","
                        + "\"emergencyContactMasked\":\"13800138000\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.gender").isEqualTo("F")
                .jsonPath("$.phoneMasked").isEqualTo("*******8000")
                .jsonPath("$.emergencyContactMasked").isEqualTo("*********00");
    }

    @Test
    void counselorCannotStartStudentChat() {
        webTestClient.post()
                .uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth("admin", "admin123"))
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void studentCannotStartChatBeforeRequiredConsents() {
        webTestClient.post()
                .uri("/api/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue("{\"message\":\"hello\"}")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void studentCanGrantAndReadVersionedConsent() {
        webTestClient.post()
                .uri("/api/student/consents")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue("{\"consentType\":\"SENSITIVE_DATA_PROCESSING\",\"version\":\"v1\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.consentType").isEqualTo("SENSITIVE_DATA_PROCESSING")
                .jsonPath("$.status").isEqualTo("GRANTED");

        webTestClient.post()
                .uri("/api/student/consents")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue("{\"consentType\":\"SENSITIVE_DATA_PROCESSING\",\"version\":\"v1\"}")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/student/consents")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].consentType").isEqualTo("SENSITIVE_DATA_PROCESSING");

        assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(log -> log.getActorUsername().equals("student")
                        && log.getAction() == AuditAction.CONSENT_GRANT
                        && log.getOutcome() == AuditOutcome.SUCCESS);
    }

    @Test
    void systemAdminCannotReadStudentConsent() {
        webTestClient.get()
                .uri("/api/student/consents")
                .headers(headers -> headers.setBasicAuth("system-admin", "system123"))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(log -> log.getActorUsername().equals("system-admin")
                        && log.getAction() == AuditAction.CONSENT_LIST_VIEW
                        && log.getOutcome() == AuditOutcome.DENIED);
    }

    private PsychologicalReport reportFor(UserAccount user, String summary) {
        return reportFor(user, summary, RiskLevel.NONE);
    }

    private PsychologicalReport reportFor(UserAccount user, String summary, RiskLevel riskLevel) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setContent(summary);
        report.setNeedsRag(false);
        report.setEmotion(EmotionLabel.NORMAL);
        report.setEmotionScore(0.1);
        report.setRiskLevel(riskLevel);
        report.setConfidence(0.9);
        report.setSummary(summary);
        report.setEmotionTags("normal");
        report.setExcelStatus(ToolStatus.SKIPPED);
        report.setEmailStatus(ToolStatus.SKIPPED);
        return report;
    }
}
