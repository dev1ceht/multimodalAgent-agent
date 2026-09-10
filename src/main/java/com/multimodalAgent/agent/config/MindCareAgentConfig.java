package com.multimodalAgent.agent.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit ChatModel for ReactAgent; legacy AiClient wiring remains unchanged. */
@Configuration
public class MindCareAgentConfig {

    @Bean(name = "agentChatModel")
    public ChatModel agentChatModel(
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties
    ) {
        String provider = value(applicationProperties.getAi().getProvider()).toLowerCase();
        String model = configuredModel(applicationProperties, agentProperties, provider);
        if ("ollama".equals(provider)
                && !value(applicationProperties.getAi().getOllama().getBaseUrl()).isBlank()
                && !model.isBlank()) {
            OllamaChatOptions options = OllamaChatOptions.builder()
                    .model(model)
                    .temperature(applicationProperties.getAi().getTemperature())
                    .numPredict(applicationProperties.getAi().getMaxTokens())
                    .numCtx(applicationProperties.getAi().getContextWindow())
                    .repeatPenalty(1.12)
                    .keepAlive("5m")
                    .disableThinking()
                    .internalToolExecutionEnabled(false)
                    .build();
            return OllamaChatModel.builder()
                    .ollamaApi(OllamaApi.builder()
                            .baseUrl(applicationProperties.getAi().getOllama().getBaseUrl())
                            .build())
                    .defaultOptions(options)
                    .build();
        }
        if ("openai".equals(provider)
                && !value(applicationProperties.getAi().getOpenai().getApiKey()).isBlank()
                && !model.isBlank()) {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(applicationProperties.getAi().getTemperature())
                    .maxTokens(applicationProperties.getAi().getMaxTokens())
                    .internalToolExecutionEnabled(false)
                    .build();
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(applicationProperties.getAi().getOpenai().getBaseUrl())
                    .apiKey(applicationProperties.getAi().getOpenai().getApiKey())
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
        }
        return new UnconfiguredAgentChatModel();
    }

    private static String configuredModel(
            multimodalAgentProperties applicationProperties,
            MindCareAgentProperties agentProperties,
            String provider
    ) {
        if (!value(agentProperties.getModel()).isBlank()) {
            return agentProperties.getModel().trim();
        }
        if ("openai".equals(provider)) {
            return value(applicationProperties.getAi().getOpenai().getModel());
        }
        return value(applicationProperties.getAi().getOllama().getModel());
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    /** Safe default so legacy mode starts without a cloud key or local model service. */
    static final class UnconfiguredAgentChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(
                    "当前 Agent 模型尚未配置，暂时无法可靠回答。请稍后重试。"))));
        }
    }
}
