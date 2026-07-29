package com.multimodalAgent.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.evaluation.EvaluationTraceService;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.Test;

class PsychologicalAssessmentServiceTests {

    @Test
    void nonDiagnosticStatesMapToLegacyReportWithoutDrivingSafetyRisk() {
        CapturingAiClient aiClient = new CapturingAiClient("""
                {
                  "observedStates":["LOW_MOOD","SLEEP_DIFFICULTY"],
                  "functionalImpact":"MILD",
                  "safetyRisk":"LOW",
                  "evidence":["持续低落","最近睡不好"],
                  "confidence":0.84,
                  "summary":"用户描述了低落和睡眠困难，但未表达即时危险。"
                }
                """);

        PsychologyAssessment assessment = service(aiClient).assess(
                "最近心情比较低落，也睡不好。");

        assertEquals(EmotionLabel.DEPRESSED, assessment.emotion());
        assertEquals(3.0, assessment.emotionScore());
        assertEquals(RiskLevel.LOW, assessment.risk());
        assertEquals(0.84, assessment.confidence());
        assertTrue(aiClient.schema.path("properties").has("observedStates"));
        assertFalse(aiClient.schema.path("properties").has("emotionScore"));
        assertFalse(aiClient.messages.get(0).content().contains("\"DEPRESSED\""));
    }

    @Test
    void highSafetyRiskAlwaysMapsToHighRiskLegacyReport() {
        CapturingAiClient aiClient = new CapturingAiClient("""
                {
                  "observedStates":["HOPELESS"],
                  "functionalImpact":"SIGNIFICANT",
                  "safetyRisk":"HIGH",
                  "evidence":["无法保证自己安全"],
                  "confidence":0.93,
                  "summary":"存在明确的当前安全信号。"
                }
                """);

        PsychologyAssessment assessment = service(aiClient).assess(
                "我现在无法保证自己安全，但没有使用词库中的固定表达。");

        assertEquals(EmotionLabel.HIGH_RISK, assessment.emotion());
        assertEquals(4.0, assessment.emotionScore());
        assertEquals(RiskLevel.HIGH, assessment.risk());
    }

    @Test
    void invalidStructuredResultFallsBackWithoutAcceptingUnknownLabels() {
        CapturingAiClient aiClient = new CapturingAiClient("""
                {
                  "observedStates":["CLINICALLY_DEPRESSED"],
                  "functionalImpact":"SEVERE",
                  "safetyRisk":"CRITICAL",
                  "evidence":[],
                  "confidence":2,
                  "summary":"invalid"
                }
                """);

        PsychologyAssessment assessment = service(aiClient).assess("今天正常上课。");

        assertEquals(EmotionLabel.NORMAL, assessment.emotion());
        assertEquals(RiskLevel.NONE, assessment.risk());
    }

    @Test
    void riskFloorPreservesUnifiedAssessmentEvidence() {
        PsychologyAssessment assessment = service(new CapturingAiClient("""
                {
                  "observedStates":["LOW_MOOD"],
                  "functionalImpact":"SIGNIFICANT",
                  "safetyRisk":"MEDIUM",
                  "evidence":["已经无法正常上课"],
                  "confidence":0.88,
                  "summary":"低落状态已经明显影响日常功能。"
                }
                """)).assess("这种状态已经让我无法正常上课。");

        PsychologyAssessment raised = assessment.withRiskFloor(RiskLevel.HIGH);

        assertEquals(RiskLevel.HIGH, raised.risk());
        assertEquals(assessment.observedStates(), raised.observedStates());
        assertEquals(assessment.functionalImpact(), raised.functionalImpact());
        assertEquals(assessment.evidence(), raised.evidence());
    }

    private PsychologicalAssessmentService service(AiClient aiClient) {
        ObjectMapper objectMapper = new ObjectMapper();
        EvaluationTraceService traceService = new EvaluationTraceService(
                new multimodalAgentProperties(),
                objectMapper);
        return new PsychologicalAssessmentService(aiClient, objectMapper, traceService);
    }

    private static final class CapturingAiClient implements AiClient {
        private final String response;
        private List<AiMessage> messages = List.of();
        private com.fasterxml.jackson.databind.JsonNode schema;

        private CapturingAiClient(String response) {
            this.response = response;
        }

        @Override
        public String complete(List<AiMessage> messages) {
            this.messages = messages;
            return response;
        }

        @Override
        public String completeJson(List<AiMessage> messages, Map<String, Object> schema) {
            this.messages = messages;
            this.schema = new ObjectMapper().valueToTree(schema);
            return response;
        }

        @Override
        public Flux<String> stream(List<AiMessage> messages) {
            return Flux.empty();
        }
    }
}
