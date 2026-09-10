package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable, privacy-safe lifecycle metadata for one agent run. */
@Entity
@Table(name = "agent_runs")
public class AgentRunRecord {

    @Id
    @Column(name = "run_id", length = 36)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "session_public_id", nullable = false, length = 64)
    private String sessionPublicId;

    @Column(name = "execution_mode", nullable = false, length = 20)
    private String executionMode;

    @Column(length = 160)
    private String model;

    @Column(name = "schema_version", nullable = false, length = 80)
    private String schemaVersion;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "needs_rag", nullable = false)
    private boolean needsRag;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "first_status_at")
    private Instant firstStatusAt;

    @Column(name = "first_answer_at")
    private Instant firstAnswerAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "model_call_count", nullable = false)
    private int modelCallCount;

    @Column(name = "tool_call_count", nullable = false)
    private int toolCallCount;

    @Column(name = "tool_rejection_count", nullable = false)
    private int toolRejectionCount;

    @Column(name = "budget_terminated", nullable = false)
    private boolean budgetTerminated;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public String getSessionPublicId() { return sessionPublicId; }
    public void setSessionPublicId(String value) { sessionPublicId = value; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) { executionMode = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String value) { riskLevel = value; }
    public boolean isNeedsRag() { return needsRag; }
    public void setNeedsRag(boolean value) { needsRag = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFirstStatusAt() { return firstStatusAt; }
    public void setFirstStatusAt(Instant value) { firstStatusAt = value; }
    public Instant getFirstAnswerAt() { return firstAnswerAt; }
    public void setFirstAnswerAt(Instant value) { firstAnswerAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public int getModelCallCount() { return modelCallCount; }
    public void setModelCallCount(int value) { modelCallCount = value; }
    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int value) { toolCallCount = value; }
    public int getToolRejectionCount() { return toolRejectionCount; }
    public void setToolRejectionCount(int value) { toolRejectionCount = value; }
    public boolean isBudgetTerminated() { return budgetTerminated; }
    public void setBudgetTerminated(boolean value) { budgetTerminated = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
}
