# Persist notification attempts separately from delivery tasks

- Status: Accepted
- Date: 2026-08-04

## Context

`AlertRecord` represents the current logical alert for one recipient. `DeliveryTask` represents durable work that can be claimed and retried. Neither model can preserve the history of each concrete notification attempt: a retry currently changes the task counters and status instead of leaving an auditable attempt record.

The platform design requires every notification attempt to be written to `notification_records`.

## Decision

Create one `NotificationRecord` for each alert-task claim. The record stores the business channel, recipient type, masked recipient, attempt number, stable idempotency key, lifecycle status, error message, and timestamps. It links to the `DeliveryTask` that produced the attempt.

The idempotency key remains stable across retries; `(delivery_task_id, attempt_number)` identifies one attempt. `AlertRecord` remains the logical alert status, while `DeliveryTask` remains the scheduling and retry state. Excel export tasks do not create notification records.

## Consequences

- Retry history and notification failure reasons are queryable without reconstructing them from mutable task state.
- A successful or failed attempt is recorded in the same transaction that updates the delivery task state.
- The notification table grows with retries and will need retention/archival policy in a later operational phase.
- When the future `risk_events` model is introduced, the record can gain a direct risk-event link without changing the attempt lifecycle.
