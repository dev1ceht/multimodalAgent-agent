package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable metadata for one allowlisted tool execution; no arguments or raw results are stored. */
@Entity
@Table(name = "agent_tool_executions")
public class AgentToolExecutionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "tool_call_id", nullable = false, length = 80)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false, length = 80)
    private String toolName;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "result_chars", nullable = false)
    private int resultChars;

    @Column(name = "policy_enforced", nullable = false)
    private boolean policyEnforced;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String value) { toolCallId = value; }
    public String getToolName() { return toolName; }
    public void setToolName(String value) { toolName = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public int getResultChars() { return resultChars; }
    public void setResultChars(int value) { resultChars = value; }
    public boolean isPolicyEnforced() { return policyEnforced; }
    public void setPolicyEnforced(boolean value) { policyEnforced = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
}
