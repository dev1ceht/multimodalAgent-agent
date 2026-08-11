package com.multimodalAgent.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BenchmarkModelDefinitionTests {

    private static final Pattern SYSTEM_PROMPT = Pattern.compile(
            "(?s)^SYSTEM \\\"\\\"\\\"\\R(.*?)\\R\\\"\\\"\\\"$",
            Pattern.MULTILINE);

    @Test
    void benchmarkModelsEmbedTheSameNonEmptySystemPrompt() throws IOException {
        String qwen25Prompt = systemPrompt("models/Modelfile.qwen25-benchmark");
        String qwen35Prompt = systemPrompt("models/Modelfile.qwen35-benchmark");

        assertThat(qwen25Prompt).isNotBlank();
        assertThat(qwen35Prompt).isEqualTo(qwen25Prompt);
    }

    private String systemPrompt(String path) throws IOException {
        String modelDefinition = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Matcher matcher = SYSTEM_PROMPT.matcher(modelDefinition);
        assertThat(matcher.find())
                .as("%s must contain a triple-quoted SYSTEM prompt", path)
                .isTrue();
        return matcher.group(1);
    }
}
