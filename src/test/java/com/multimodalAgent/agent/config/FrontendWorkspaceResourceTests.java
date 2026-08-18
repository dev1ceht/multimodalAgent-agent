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
    void exposesBrowserRecordingControlsAndExistingMultimodalUploadPath() throws IOException {
        String html = resource("static/index.html");
        String javascript = resource("static/app.js");
        String styles = resource("static/styles.css");

        assertThat(html).contains(
                "id=\"audioInput\"",
                "id=\"startRecordingButton\"",
                "id=\"stopRecordingButton\"",
                "id=\"recordedAudio\"",
                "id=\"recordingStatus\"",
                "aria-live=\"polite\"");
        assertThat(javascript).contains(
                "getUserMedia({ audio: true })",
                "new window.MediaRecorder",
                "recordedAudioFile",
                "URL.revokeObjectURL",
                "/api/chat/multimodal/stream",
                "body.append(key, file)");
        assertThat(styles).contains(
                ".recording-panel",
                ".recording-indicator",
                ".recorded-audio");
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

    @Test
    void exposesKnowledgeLifecycleManagementForSystemAdministrators() throws IOException {
        String html = resource("static/index.html");
        String javascript = resource("static/app.js");
        String styles = resource("static/styles.css");

        assertThat(html).contains(
                "id=\"knowledgePanel\"",
                "id=\"knowledgeStatusCards\"",
                "id=\"knowledgeDocumentRows\"",
                "id=\"knowledgeDocumentForm\"",
                "id=\"knowledgeContent\"",
                "id=\"knowledgeVersionRows\"");
        assertThat(javascript).contains(
                "/api/admin/knowledge/status",
                "/api/admin/knowledge/documents",
                "/api/admin/knowledge/versions",
                "? \"PUT\" : \"POST\"",
                "method: \"DELETE\"",
                "/retry",
                "manageKnowledge");
        assertThat(styles).contains(
                ".knowledge-panel",
                ".knowledge-document-grid",
                ".knowledge-version-list");
    }

    private String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
