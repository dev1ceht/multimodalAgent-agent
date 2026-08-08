package com.multimodalAgent.agent.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OperationsConfig {

    @Bean
    public Clock operationsClock() {
        return Clock.systemUTC();
    }
}
