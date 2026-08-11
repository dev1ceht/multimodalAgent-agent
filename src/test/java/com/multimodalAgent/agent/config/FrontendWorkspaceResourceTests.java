package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FrontendWorkspaceResourceTests {

    @Test
    void exposesHighPriorityWorkspaceSurfaces() throws IOException {
        String html = resource("static/index.html");

        assertThat(html).contains(
                "id=\"supportStatusRows\"",
                "id=\"operationsPanel\"",
                "id=\"operationsStats\"",
                "id=\"caseWorkbench\"",
                "id=\"riskCaseRows\"");
    }

    @Test
    void wiresHighPriorityEndpointsAndStaffRoles() throws IOException {
        String javascript = resource("static/app.js");

        assertThat(javascript).contains(
                "/api/student/support-status",
                "/api/admin/operations/overview",
                "/api/admin/risk-cases",
                "/api/admin/risk-cases/referral-targets",
                "/referrals",
                "/interventions",
                "ROLE_COUNSELOR",
                "ROLE_PSYCHOLOGY_CENTER",
                "ROLE_SCHOOL_ADMIN");
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
