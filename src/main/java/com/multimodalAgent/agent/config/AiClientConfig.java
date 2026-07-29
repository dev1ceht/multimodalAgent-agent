package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.HeuristicAiClient;
import com.multimodalAgent.agent.service.ai.OllamaAiClient;
import com.multimodalAgent.agent.service.ai.SpringAiChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 大模型客户端装配配置。
 *
 * <p>根据 application.yml 或环境变量选择本地项目模型、OpenAI 或 mock 客户端，
 * 让业务服务只依赖统一的 {@link AiClient} 接口。</p>
 */
@Configuration
public class AiClientConfig {

    @Bean
    public AiClient aiClient(multimodalAgentProperties properties) {
        String provider = properties.getAi().getProvider().toLowerCase();
        if ("ollama".equals(provider)) {
            return new OllamaAiClient(properties);
        }
        if ("openai".equals(provider)) {
            if (properties.getAi().getOpenai().getApiKey().isBlank()) {
                throw new IllegalStateException("AI_PROVIDER=openai requires OPENAI_API_KEY.");
            }
            OpenAiChatModel model = openAiChatModel(properties);
            return new SpringAiChatClient(model, model);
        }
        return new HeuristicAiClient();
    }

    private OpenAiChatModel openAiChatModel(multimodalAgentProperties properties) {
        multimodalAgentProperties.OpenAi openai = properties.getAi().getOpenai();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openai.getBaseUrl())
                .apiKey(openai.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openai.getModel())
                .temperature(properties.getAi().getTemperature())
                .maxTokens(properties.getAi().getMaxTokens())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}
