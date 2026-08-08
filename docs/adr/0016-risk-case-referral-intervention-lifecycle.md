# ADR 0016: Model high-risk follow-up as a case, referral, and intervention lifecycle

## Status

Accepted

## Context

The platform already creates high-risk psychological reports and durable alert delivery tasks, but an alert is not a care workflow. A real campus deployment needs to distinguish an automated signal from the human case opened for follow-up, the handoff to a care destination, and the factual record of interventions. Reusing delivery status for care status would make a failed email look like a resolved case and would make retries impossible to reason about.

## Decision

1. Open at most one durable `RiskCase` for each high-risk student report. The case snapshots the report risk level and starts in `OPEN`.
2. Keep the case lifecycle explicit: `OPEN`, `ACKNOWLEDGED`, `REFERRED`, `IN_PROGRESS`, `RESOLVED`, and `CLOSED`. State transitions are validated by the domain model and repeated transitions are idempotent.
3. Represent each handoff as a separate `Referral`. Referrals have their own `PENDING`, `ACCEPTED`, `DECLINED`, `COMPLETED`, and `CANCELLED` lifecycle so a declined handoff does not erase the case history.
4. Represent each human action as an append-only `InterventionRecord` with an author, type, notes, occurrence time, and optional follow-up time. Student-facing responses omit notes and author details.
5. Psychology-center staff may manage high-risk student cases. Counselors may manage only cases whose student is covered by an enabled assignment. System administrators and school administrators do not receive raw case access from their administrative role.
6. Students may read a deliberately small support-status projection for their own cases. It is not a report, transcript, or clinical record.

## Consequences

- Alert delivery remains an operational notification concern and cannot close or resolve a care case.
- The case, referral, and intervention histories can be audited independently and retried without losing prior decisions.
- Future risk-event types can open cases through the same lifecycle without changing referral or intervention semantics.
- A later school-administrator dashboard must use an aggregate read model rather than widening raw case access.
