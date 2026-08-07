# ADR 0014: Make demo accounts opt-in and keep report routes scope-aware

## Status

Accepted

## Context

The application seeds `admin/admin123` and `student/student123` during startup. That is convenient for a local demonstration, but a production startup path must not create predictable credentials by default.

The report API also has two different data scopes:

- `/api/reports/me` returns reports owned by the authenticated user.
- `/api/admin/reports` and the remaining admin report routes are administrative views.

The previous matcher for `/api/reports/**` applied the admin role to both scopes, so a student could not reach the endpoint that already enforced the student's own-user query.

## Decision

1. Add `multimodal-agent.security.demo-accounts-enabled`, mapped from `DEMO_ACCOUNTS_ENABLED`, with a secure default of `false`.
2. Seed demo users only when the flag is enabled. The local `scripts/run-dev.sh` enables it explicitly for the existing demo workflow.
3. Match `/api/reports/me` before `/api/reports/**` and require authentication for the former; keep the broader report routes restricted to `ADMIN`.
4. Do not delete or rewrite existing accounts during startup. Production account provisioning and removal remain explicit deployment or migration operations.
5. Keep assignment-based counselor and psychological-center scopes out of this slice because the current domain has no assignment model. They require a separate data-model and authorization slice.

## Consequences

- A deployment that does not explicitly enable demo accounts no longer receives predictable startup credentials.
- Local developers retain one-command demo startup, while the security-sensitive behavior is visible in configuration.
- Student report access is both route-scoped and query-scoped; administrator routes remain protected by role.
- The current `/api/admin/reports` compatibility route still reflects the existing single `ROLE_ADMIN` model; it is not the final school-administrator aggregate boundary.
- The next authorization slice must introduce assignments, scope predicates, and tests for counselor and psychological-center access rather than inferring those scopes from roles alone.
