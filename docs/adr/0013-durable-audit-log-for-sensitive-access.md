# Establish a durable audit log for sensitive access

- Status: Accepted
- Date: 2026-08-07

## Context

Reports, conversations, alerts, Excel records, and knowledge-base management are sensitive operations. Runtime metrics can show that an endpoint was called, but they cannot answer which account accessed which resource or whether a forbidden request was rejected. The service also must not turn report content, conversation identifiers, uploaded filenames, or request payloads into another sensitive data store.

## Decision

Introduce `AuditLog` and `AuditLogService` as the application-owned audit seam. The service records:

- an actor snapshot, using the authenticated user ID and username when available;
- an optional target student ID, plus bounded client IP and User-Agent context;
- a fixed action, resource type, and outcome vocabulary;
- an HMAC-SHA-256 resource reference so repeated access to the same resource can be correlated without storing the raw identifier or exposing low-entropy IDs to dictionary attacks;
- the bounded `X-Request-Id` value from `RequestCorrelationWebFilter`;
- only whitelisted metadata such as result counts, chunk counts, file type, scope, and publication status.

Successful sensitive views and knowledge mutations record after the business operation completes. Out-of-scope conversation reads record `DENIED`. Authenticated authorization failures are handled by `AuditAccessDeniedHandler`, which writes an `ACCESS_DENIED` event before returning `403`. The administrator endpoint exposes the latest 100 records and is protected by the existing `/api/admin/**` rule. Reading the audit log is intentionally not audited to avoid recursive audit events.

The HMAC key is supplied through `AUDIT_RESOURCE_HASH_SECRET`; the bundled default is for local development only and must be replaced in deployed environments.

## Consequences

The project has a durable, queryable trail for the sensitive operations currently exposed by the API, with request correlation available for incident investigation. Audit records are safe to export to an operations dashboard because raw report content, message content, filenames, paths, and exception messages are excluded.

This is a foundation rather than a complete compliance platform. Production deployments should later add retention/archival policy, append-only storage controls, centralized log shipping, and a migration-managed schema instead of relying on Hibernate `ddl-auto=update`.
