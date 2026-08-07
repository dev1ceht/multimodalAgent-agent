package com.multimodalAgent.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.multimodalAgent.agent.domain.AuditAction;
import com.multimodalAgent.agent.domain.AuditOutcome;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.AuditLogRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
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

    @BeforeEach
    void setUpReportsForDifferentUsers() {
        psychologicalReportRepository.deleteAll();
        UserAccount student = userAccountRepository.findByUsername("student").orElseThrow();
        UserAccount admin = userAccountRepository.findByUsername("admin").orElseThrow();
        psychologicalReportRepository.save(reportFor(student, "student-only-report"));
        psychologicalReportRepository.save(reportFor(admin, "admin-owned-report"));
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
                .expectStatus().isOk();
    }

    private PsychologicalReport reportFor(UserAccount user, String summary) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setContent(summary);
        report.setNeedsRag(false);
        report.setEmotion(EmotionLabel.NORMAL);
        report.setEmotionScore(0.1);
        report.setRiskLevel(RiskLevel.NONE);
        report.setConfidence(0.9);
        report.setSummary(summary);
        report.setEmotionTags("normal");
        report.setExcelStatus(ToolStatus.SKIPPED);
        report.setEmailStatus(ToolStatus.SKIPPED);
        return report;
    }
}
