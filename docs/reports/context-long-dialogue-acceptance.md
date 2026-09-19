# Context long-dialogue acceptance report

Date: 2026-09-20

## Automated protocol comparison

ContextLongDialogueAcceptanceTests generates 50 deterministic synthetic conversations. Each has
30–100 user/assistant turns, an early constraint, a later correction, periodic tool-like evidence,
and a unique current input.

| Check | window | budget | summary |
|---|---:|---:|---:|
| Conversations evaluated | 50 | 50 | 50 |
| Requests above the configured input ceiling | 50 | 0 | 0 |
| Current input present exactly once | 50/50 | 50/50 | 50/50 |
| System instruction retained | 50/50 | 50/50 | 50/50 |
| Early constraint and later correction retained in validated summary data | n/a | n/a | 50/50 |

The window result is a baseline observation, not a failure of that legacy mode: it intentionally
does not enforce the hard ceiling. Budget and summary modes both enforced the 1,300-token synthetic
input ceiling.

## Recovery evidence

ContextSummaryWorkerTests covers these state transitions:

- a message-count candidate batch is reduced by the compiler's token limit and the committed
  watermark advances only through the fitted contiguous prefix;
- compiler failure retains the previous summary/watermark, clears the lease, and enters
  RETRY_WAIT;
- an expired PROCESSING lease is reclaimed and completed;
- a stale result whose base version changed after claim is discarded, the job returns to PENDING,
  and the CAS-conflict metric increments;
- summary latency/outcome, queue depth, version, covered-through, gap, lag, and CAS-conflict
  instrumentation uses bounded labels.

The compiler separately rejects an oversized first uncovered message with
summary_source_message_too_large; the worker retries and eventually marks the job failed without
skipping that source ID.

## Remaining production gate

This automated suite proves request-size, ordering, watermark, and recovery properties. It does not
measure semantic summary quality with the deployed model. Before broad summary rollout, run the
same corpus through the configured model and have labeled checks confirm at least 95% key-constraint
retention, no severe assistant-suggestion/user-fact confusion, and valid source traceability.

The current AiClient returns text without provider usage metadata, so this report contains
estimated input tokens only. Provider-reported actual input tokens, first-token latency, model cost,
and estimator error remain deployment-probe measurements.
