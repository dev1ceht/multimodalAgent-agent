# ADR 0018: Add case concurrency tokens, SLA deadlines, and an incremental migration baseline

## Status

Accepted

## Context

Case and referral updates are human workflow writes. Two staff members can read the same case, make different decisions, and submit them close together. Without a concurrency token, the later write can silently overwrite the earlier decision. The case workflow also needs an explicit response deadline so an operations dashboard can identify work that needs escalation rather than relying on ad hoc timestamps.

The application previously relied on Hibernate `ddl-auto=update`. That is useful for local development but does not provide an auditable production schema history. Existing MySQL deployments already have tables created by the application, so the migration rollout must be incremental and must not pretend that a complete from-scratch schema exists yet.

## Decision

1. Add a JPA `@Version` token to `RiskCase` and `Referral`. Staff mutation responses expose the current version, and status requests may send `expectedVersion`; a mismatch is rejected with HTTP 409 before the domain transition. Database-level optimistic locking remains the final guard for concurrent transactions.
2. Store `RiskCase.slaDueAt` and calculate it when a high-risk case is opened from the configured high-risk response duration. When a referral request omits `dueAt`, calculate it from the configured referral response duration. Explicit deadlines are preserved.
3. Define overdue cases as cases whose SLA deadline is before the observation time and whose status is `OPEN`, `ACKNOWLEDGED`, `REFERRED`, or `IN_PROGRESS`. Define overdue referrals as the existing pending/accepted referral query with a past `dueAt`.
4. Add `overdueCases` to the aggregate-only school operations read model. This is a dashboard/alerting signal and does not expose case identifiers or sensitive content.
5. Add Flyway and a MySQL-profile migration baseline. `V1` marks the existing schema; `V2` adds the version and SLA columns plus the operational index and backfills deadlines for existing cases using the initial two-hour HIGH-case default. The MySQL profile uses `ddl-auto=validate` and `baseline-on-migrate`; the explicit local profile keeps schema creation enabled until a full bootstrap migration is available.

## Consequences

- Staff clients can implement compare-and-swap updates and receive a stable conflict response instead of silently losing decisions.
- SLA configuration becomes environment-controlled and observable through aggregate metrics; notifications can later consume the same overdue query without changing the domain model.
- The migration path is auditable for existing MySQL installations while remaining intentionally incremental. A future release still needs a complete from-scratch bootstrap migration before `ddl-auto` can be disabled in every environment.
- Version and deadline fields are internal workflow metadata and are not added to the student support projection.
