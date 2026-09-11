package com.multimodalAgent.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration owned by the SAA Agent path. */
@ConfigurationProperties(prefix = "multimodal-agent.agent")
public class MindCareAgentProperties {

    private String mode = "saa";
    private String model = "";
    private int maxModelCalls = 6;
    private int maxToolCalls = 8;
    private int maxIdenticalToolCalls = 2;
    private int maxArgumentRepairs = 1;
    private Duration timeout = Duration.ofSeconds(60);
    private Duration toolTimeout = Duration.ofSeconds(10);
    private int maxToolResultChars = 6000;
    private int maxTotalToolResultChars = 18000;
    private Duration metadataRetention = Duration.ofDays(7);
    private String schemaVersion = "mindcare-agent-v1";
    private boolean toolCallingVerified;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxModelCalls() {
        return maxModelCalls;
    }

    public void setMaxModelCalls(int maxModelCalls) {
        this.maxModelCalls = maxModelCalls;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxIdenticalToolCalls() {
        return maxIdenticalToolCalls;
    }

    public void setMaxIdenticalToolCalls(int maxIdenticalToolCalls) {
        this.maxIdenticalToolCalls = maxIdenticalToolCalls;
    }

    public int getMaxArgumentRepairs() {
        return maxArgumentRepairs;
    }

    public void setMaxArgumentRepairs(int maxArgumentRepairs) {
        this.maxArgumentRepairs = maxArgumentRepairs;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getToolTimeout() {
        return toolTimeout;
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public int getMaxToolResultChars() {
        return maxToolResultChars;
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        this.maxToolResultChars = maxToolResultChars;
    }

    public int getMaxTotalToolResultChars() {
        return maxTotalToolResultChars;
    }

    public void setMaxTotalToolResultChars(int maxTotalToolResultChars) {
        this.maxTotalToolResultChars = maxTotalToolResultChars;
    }

    public Duration getMetadataRetention() {
        return metadataRetention;
    }

    public void setMetadataRetention(Duration metadataRetention) {
        this.metadataRetention = metadataRetention;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isToolCallingVerified() {
        return toolCallingVerified;
    }

    public void setToolCallingVerified(boolean toolCallingVerified) {
        this.toolCallingVerified = toolCallingVerified;
    }

    public boolean isSaaMode() {
        return "saa".equalsIgnoreCase(mode);
    }
}
