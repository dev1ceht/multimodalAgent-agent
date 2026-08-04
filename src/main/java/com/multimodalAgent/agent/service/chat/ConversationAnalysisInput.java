package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import java.util.Objects;

/**
 * Input owned by the conversation analysis module.
 *
 * <p>It intentionally excludes identity and raw content, which belong to
 * report and response policy.</p>
 */
public record ConversationAnalysisInput(
        String modelInput,
        ConversationHistory previousHistory,
        MultimodalAnalysis multimodalAnalysis
) {

    public ConversationAnalysisInput {
        modelInput = Objects.requireNonNull(modelInput, "modelInput");
        previousHistory = Objects.requireNonNull(previousHistory, "previousHistory");
    }
}
