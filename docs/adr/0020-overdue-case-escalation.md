# ADR 0020: Create an idempotent overdue-case escalation loop

## Status

Accepted

## Context

The case SLA and aggregate overdue count identify work that has passed its response deadline, but an operations team also needs a durable signal to act on that breach. A scheduled process must be safe to run on every application instance, must tolerate notification retries, and must not turn a delivery result into a case-lifecycle decision.

The existing delivery queue already provides durable tasks, leases, idempotency keys, retry scheduling, and concrete notification-attempt history. Creating a second queue for overdue notifications would duplicate failure semantics and make operational reporting harder to reconcile.

## Decision

1. Define an overdue case as an active `RiskCase` whose `slaDueAt` is before the observation time. The active statuses remain `OPEN`, `ACKNOWLEDGED`, `REFERRED`, and `IN_PROGRESS`.
2. Run a bounded scheduled scan with a pessimistic lock on the selected case rows. For each eligible case, create one `RISK_CASE_ESCALATION` delivery task per configured staff recipient using the idempotency key `risk-case-overdue:{caseId}:{recipient}`.
3. Persist an `overdueEscalatedAt` marker on the case in the same transaction as task creation. The marker makes the system-generated escalation event one-time; task retries remain independent and are handled by the existing delivery worker.
4. Record a privacy-preserving audit event when an escalation is created. The audit resource is the hashed risk-case reference, the student identifier is retained only in the dedicated student field, and no report content, recipient address, or staff note is stored in details.
5. Do not transition the case, resolve it, or suppress future staff actions when a delivery task succeeds or fails. Delivery status is an operational outcome, not a human follow-up outcome.

## Consequences

- Multiple application instances converge on one durable escalation event because the case marker and task idempotency key are protected by the transaction and unique key.
- Existing lease recovery, retry backoff, notification attempt history, and delivery metrics cover overdue escalation without a second delivery subsystem.
- A case with no configured recipient remains unmarked and can be picked up after configuration is corrected; operators still need configuration monitoring to notice the missing recipient.
- The first slice sends the same bounded overdue signal to configured staff recipients. Per-department routing and multi-level escalation windows require a separate policy decision.
