# Context budget and rolling-summary runbook

## Scope

This runbook covers input-budget rejection, rolling-summary backlog, retries, lease recovery,
compare-and-set conflicts, and rollback. Metrics contain bounded modes and outcomes only; user and
session identifiers remain in controlled logs and database records, not Prometheus labels.

## Modes and safe rollback

CHAT_CONTEXT_MODE accepts summary, budget, or window.

- summary applies the hard input budget and injects the latest validated rolling summary.
- budget keeps the hard input budget but stops summary injection and worker processing. Use this
  as the first rollback step.
- window restores the legacy message-window behavior and its context-overflow risk. Use it only
  when budget fitting itself is the suspected fault.

To roll back summary behavior, set CHAT_CONTEXT_MODE=budget, restart the application instances,
and verify new multimodalagent_context_budget_decisions_total samples have mode="budget".
Existing summary and job rows may remain; they are session-scoped and are deleted with the session.
Returning to summary resumes due or expired jobs without resetting a valid watermark.

## Capacity controls

Tune these environment variables together:

| Variable | Effect |
|---|---|
| AI_CONTEXT_WINDOW | Configured model context capacity. Confirm against the deployed model. |
| AI_MAX_TOKENS | Output reservation subtracted from the input ceiling. |
| CHAT_CONTEXT_SAFETY_MARGIN_TOKENS | Conservative allowance for provider/template differences. |
| CHAT_CONTEXT_SUMMARY_BATCH_MAX_MESSAGES | Upper message-count bound; the compiler also enforces the input-token ceiling. |
| CHAT_CONTEXT_SUMMARY_MAX_TOKENS | Maximum validated summary JSON size. |
| CHAT_CONTEXT_SUMMARY_MAX_ATTEMPTS | Attempts before a job becomes FAILED. |
| CHAT_CONTEXT_SUMMARY_LEASE_SECONDS | Must exceed model timeout plus database commit margin. |
| CHAT_CONTEXT_SUMMARY_BASE_RETRY_DELAY_SECONDS | Base for bounded exponential retry. |

Increase model-call concurrency only after confirming the online chat quota has headroom. The worker
shares the configured model adapter, so queue growth can indicate either insufficient worker
throughput or upstream model pressure.

## Alert response

| Alert | First response |
|---|---|
| ContextBudgetRejections | Compare estimated input tokens with the ceiling. Check current-input size, system/tool schema growth, model capacity, output reservation, and safety margin. Do not lower the margin until the deployed tokenizer/capacity is verified. |
| ContextSummaryFailures | Inspect the job error code and model availability. Invalid schema/source references require prompt or validator investigation; repeated oversized-source errors require manual handling of that session. |
| ContextSummaryRetryPressure | Check model timeout/rate limits and whether retry delay is causing synchronized retries. |
| ContextSummaryQueueBacklog | Check worker health, due-job count, model quota, and lease duration. Scale only within the summary-model quota. |
| ContextSummaryCasConflicts | Look for duplicate workers, long model latency, or frequent desired-watermark changes. Results are discarded safely; persistent conflicts waste quota. |
| ContextSummaryCoverageGap | Find the affected job through time-correlated controlled logs. Confirm whether the first uncovered message exceeds the summary input ceiling. The worker must not skip it. |

Useful Prometheus signals:

- multimodalagent_context_input_tokens and multimodalagent_context_input_ceiling
- multimodalagent_context_omitted_messages
- multimodalagent_context_summary_seconds
- multimodalagent_context_summary_queue_depth
- multimodalagent_context_summary_lag_messages
- multimodalagent_context_summary_coverage_gap
- multimodalagent_context_summary_cas_conflicts_total
- multimodalagent_context_tool_evidence_dropped_total

## Database checks

Inspect conversation_context_jobs by status and next_attempt_at. For a stuck session, compare
claimed_base_version with conversation_context_summaries.version, then check lease_until.
Never advance covered_through_message_id manually: it certifies a continuous validated source
prefix. A failed compile keeps the old summary and watermark. Expired PROCESSING leases are
reclaimed by the normal poller.

Deleting a chat_sessions row cascades to its summary and job through Flyway migration V11. The
foreign key prevents a completed old worker from recreating state for a deleted session.

## Deployment verification

1. Start in budget and verify both chat paths produce budget-decision metrics.
2. Enable summary for the intended environment and verify queue depth returns toward zero.
3. Confirm successful compactions increase summary-version and covered-through distributions.
4. Force a non-production compiler timeout and verify the old watermark remains, the job enters
   RETRY_WAIT, and ContextSummaryRetryPressure receives samples.
5. Delete a disposable session and verify no matching summary or job remains.

Provider-reported actual input-token usage is not exposed by the current AiClient contract.
Until that adapter returns usage metadata, treat input_tokens as a conservative estimate and
calibrate it with an external provider probe before production rollout.
