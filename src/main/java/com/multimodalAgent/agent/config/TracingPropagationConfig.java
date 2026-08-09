package com.multimodalAgent.agent.config;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Owns the cross-service trace propagation protocol used by HTTP integrations. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ContextPropagators.class)
public class TracingPropagationConfig {

    @Bean
    public ContextPropagators w3cContextPropagators() {
        return ContextPropagators.create(W3CTraceContextPropagator.getInstance());
    }
}
