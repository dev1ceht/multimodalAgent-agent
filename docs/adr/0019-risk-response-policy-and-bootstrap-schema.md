# ADR 0019: Make risk response policy explicit and bootstrap the production schema

## Status

Accepted

## Context

The application already distinguishes `NONE`, `LOW`, `MEDIUM`, and `HIGH` assessment risk levels, but the case and notification behavior was encoded as separate `HIGH` checks in different services. That makes a future policy change easy to apply inconsistently. The current safety boundary is deliberate: only `HIGH` opens the human-follow-up case and creates staff notification work; lower levels remain assessment-only in this release. `CRISIS` is not part of the current domain vocabulary.

The first Flyway release only marked an existing Hibernate-created schema as version 1. That protects existing installations, but a new MySQL database still needs a complete, reviewable schema bootstrap before production can rely on migration history alone.

## Decision

1. Represent risk response as one policy seam. The policy maps every current `RiskLevel` to two decisions: whether it opens a human-follow-up case and whether it is eligible for staff notification. The default matrix is `NONE=false/false`, `LOW=false/false`, `MEDIUM=false/false`, and `HIGH=true/true`.
2. Keep the current `HIGH`-only case and notification safety boundary. Changing the matrix is a domain decision and must be covered by policy tests; a delivery failure never changes the case lifecycle.
3. Keep the case response duration and referral response duration environment-controlled. The case SLA is only materialized for levels whose policy opens a case, which currently means `HIGH`.
4. Add Flyway `V0` as the complete schema for a fresh MySQL database and keep `V1` as the immutable pre-migration baseline marker. Existing non-empty databases use `baseline-on-migrate` at version 1 and skip `V0`/`V1`, then apply later migrations. `V2` remains the incremental change for the concurrency and SLA columns.

## Consequences

- Risk behavior has one named policy seam and cannot drift between case creation and notification enqueueing.
- Adding a new risk level, such as `CRISIS`, requires an explicit model and safety review instead of inheriting `HIGH` behavior accidentally.
- A fresh MySQL environment can be initialized by the same migration history used for production upgrades, while previously recorded migration checksums remain stable.
- Existing installations still need the normal Flyway baseline rollout and must satisfy the `V2` preconditions; local/test profiles remain free to use Hibernate schema creation.
