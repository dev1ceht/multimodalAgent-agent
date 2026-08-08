# ADR 0017: Provide a privacy-preserving school operations aggregate

## Status

Accepted

## Context

School administrators need to understand whether the support operation is keeping up with demand, but they should not receive raw psychological reports or case records merely because they have an administrative role. The existing report and case endpoints are intentionally scoped to students, counselors, and the psychology center. A separate read model is needed for operational planning.

## Decision

1. Expose one `SchoolOperationsOverview` read model to `SCHOOL_ADMIN` accounts through a dedicated operations endpoint.
2. Accept an explicit UTC half-open time window `[from, to)`; when omitted, default to the most recent 30 days. Reject future windows and windows older than the 365-day lookback.
3. Return the normalized window and generation timestamp plus only population-level counts: active students, reports by risk level, risk cases by lifecycle status, active referrals, overdue referrals, and interventions in the window.
4. Query counts as database aggregates and never map raw report content, student identifiers, case identifiers, referral reasons, or intervention notes into the response.
5. Record successful and denied overview reads in the audit log with the normalized window, but never place request content or sensitive identifiers in the audit details.
6. Keep system administrators, counselors, psychology-center reviewers, and students outside this endpoint. Their existing raw-data boundaries remain unchanged.

## Consequences

- School administrators get a useful operational view without a new path to sensitive records.
- The fixed schema can become the backend contract for a dashboard and can later be backed by a materialized read model when volume requires it.
- The first slice intentionally omits department-level breakdowns and small-cell policy; those require a separate privacy and governance decision.
