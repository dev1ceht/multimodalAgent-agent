# ADR 0024: Use bearer access tokens with rotating refresh sessions

## Status

Accepted

## Context

The application previously used HTTP Basic authentication, causing browser requests to resend the
account password. The authorization domain already distinguishes account roles from organization and
assignment scope, so changing authentication must not duplicate or weaken those live scope decisions.

## Decision

1. Issue a short-lived HMAC-signed JWT access token containing only the user ID, session ID, token ID,
   issuer, type, and timestamps.
2. Keep roles, assignments, consent, and sensitive access scope out of JWT claims. Each authenticated
   request restores an enabled `CurrentUser` from the database before applying the existing route and
   data-scope authorization rules.
3. Store a hash of an opaque refresh token in an `AuthSessionStore`. Production uses Redis; the local
   profile and integration tests use the in-memory adapter at the same seam.
4. Rotate the refresh token after every successful refresh. Reuse of an old refresh token revokes that
   session, including access tokens that have not yet expired.
5. Deliver refresh tokens only through an HttpOnly, SameSite=Strict cookie scoped to `/api/auth`.
   Production deployments must enable the Secure flag and terminate TLS.
6. Support current-session logout and account-wide logout. Bearer authentication checks the session
   store so revocation takes effect immediately.
7. Record login, refresh, logout, and authentication failures without storing passwords or tokens.
8. Coalesce refreshes within a page and serialize them across same-origin tabs with the browser Web
   Locks API. A client without Web Locks still uses page-local coalescing.

## Consequences

- HTTP Basic is disabled and clients must obtain a bearer token from `/api/auth/login`.
- Redis availability is required for production authentication. Losing Redis invalidates sessions but
  does not lose accounts or authorization data.
- Access validation performs a session lookup and an account lookup so account disablement and role
  changes are observed immediately.
- If a refresh was committed but its response is irretrievably lost, retrying the old token revokes
  the session. This fail-closed behavior preserves replay detection at the cost of requiring login
  again after that uncommon transport failure.
- School SSO remains a separate identity-provider integration. This decision does not claim that local
  JWT issuance implements SSO.
