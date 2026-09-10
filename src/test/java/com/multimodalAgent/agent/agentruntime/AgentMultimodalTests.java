package com.multimodalAgent.agent.agentruntime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalFusionService;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentMultimodalTests {

    @Test
    void agentInputKeepsActualModalitiesEvidenceAndConfidence() {
        MultimodalSignal visual = new MultimodalSignal(
                "visual",
                EmotionLabel.ANXIETY,
                2.1,
                0.88,
                "local MediaPipe face mesh evidence");
        MultimodalSignal audio = new MultimodalSignal(
                "audio",
                EmotionLabel.DEPRESSED,
                1.4,
                0.77,
                "Whisper transcript evidence");
        MultimodalAnalysis analysis = new MultimodalFusionService(
                new multimodalAgentProperties(), new ObjectMapper()).fuse(
                        "我最近很难受",
                        List.of(visual, audio));

        assertThat(analysis.signals()).containsExactly(visual, audio);
        assertThat(analysis.summary()).contains("visual=ANXIETY", "audio=DEPRESSED");
        assertThat(analysis.modelText()).contains(
                "local MediaPipe face mesh evidence",
                "Whisper transcript evidence");
        assertThat(analysis.fusedAssessment().confidence()).isGreaterThan(0.0);
        assertThat(analysis.emotionTagsJson()).contains("visual", "audio");
    }
}
