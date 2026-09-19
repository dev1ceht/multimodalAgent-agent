package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Versioned, session-scoped rolling context summary. */
@Entity
@Table(name = "conversation_context_summaries")
public class ConversationContextSummary {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long version;

    @Column(name = "covered_through_message_id", nullable = false)
    private Long coveredThroughMessageId = 0L;

    @Column(name = "summary_json", nullable = false, columnDefinition = "LONGTEXT")
    private String summaryJson = "{}";

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "token_counter_version", nullable = false, length = 64)
    private String tokenCounterVersion = "";

    @Column(name = "schema_version", nullable = false, length = 64)
    private String schemaVersion = "conversation-summary-v1";

    @Column(name = "model_key", nullable = false, length = 160)
    private String modelKey = "";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version = value; }
    public Long getCoveredThroughMessageId() { return coveredThroughMessageId; }
    public void setCoveredThroughMessageId(Long value) { coveredThroughMessageId = value; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String value) { summaryJson = value; }
    public int getTokenCount() { return tokenCount; }
    public void setTokenCount(int value) { tokenCount = value; }
    public String getTokenCounterVersion() { return tokenCounterVersion; }
    public void setTokenCounterVersion(String value) { tokenCounterVersion = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getModelKey() { return modelKey; }
    public void setModelKey(String value) { modelKey = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
}
