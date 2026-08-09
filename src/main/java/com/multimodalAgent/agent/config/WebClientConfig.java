package com.multimodalAgent.agent.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

@Configuration
/**
 * 共享 WebClient.Builder。
 *
 * <p>模型服务、embedding 服务和 HTTP MCP 工具都复用这个 Builder，便于统一扩展超时、
 * 日志或代理设置。</p>
 */
public class WebClientConfig {

    @Bean
    public WebClientCustomizer webClientTimeoutCustomizer(multimodalAgentProperties properties) {
        int connectTimeoutMs = (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(1, properties.getResilience().getHttpConnectTimeoutMs()));
        long responseTimeoutMs = Math.max(1, properties.getResilience().getHttpResponseTimeoutMs());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
        return builder -> builder.clientConnector(connector);
    }
}
