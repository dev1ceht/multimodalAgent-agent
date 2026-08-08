# ADR 0015: Use role-plus-assignment scope for sensitive report access

## Status

Accepted

## Context

The initial application used one `ROLE_ADMIN` authority for system administration and sensitive report viewing. That is not sufficient for a campus deployment: a system administrator should be able to manage configuration without automatically receiving raw psychological content, and a counselor must not see students outside their responsibility.

The current model has reports but does not yet have risk events, referrals, or intervention records. Therefore, this slice can establish the organization and assignment vocabulary without pretending that future event states already exist.

## Decision

1. Keep `ROLE_ADMIN` as the compatibility system-administrator authority and introduce explicit `COUNSELOR`, `PSYCHOLOGY_CENTER`, and `SCHOOL_ADMIN` roles.
2. Represent student academic scope with department, major, class, grade, and a `StudentProfile` linked one-to-one to `UserAccount`.
3. Represent counselor responsibility with enabled `CounselorAssignment` records. An assignment may target exactly one scope level: department, major, class, or grade.
4. Allow a student to read only their own reports. Allow a counselor to read a report only when an enabled assignment covers the report owner's profile.
5. Until risk events and referrals exist, allow psychology-center reviewers to read only `HIGH` reports. The event workflow will later add explicit referred-case predicates.
6. Store student contact fields as masked/encrypted representations and expose only masked phone data from the student profile API.
7. Store consent as versioned `ConsentRecord` history. A repeated grant is idempotent; granting a newer version revokes the previous active version for that purpose.
8. Restrict both text and multimodal chat to pure student accounts and require active privacy-notice and sensitive-data-processing consents before model or file processing.
9. Normalize profile contact fields through a shared masker at write and read boundaries; expose a stable, non-sensitive action code as the audit reason.
10. Keep school-administrator aggregate views separate from raw report endpoints. This slice does not expose raw reports to `SCHOOL_ADMIN`.
11. Make the demo operator a combined system administrator and counselor so the local workflow remains usable. Real deployments must provision separate accounts and assignments through explicit administration or migration.

## Consequences

- Sensitive access is no longer implied by the generic administration role.
- Scope decisions can be tested independently of controllers and reused by report, conversation, alert, and future risk-event APIs.
- The application now needs organization/profile/assignment data before counselor access can return records; missing scope data fails closed.
- Report and alert list limits are applied after the database has selected the authorized student scope, so unrelated records cannot consume the page window.
- The future risk-event phase must add referral state and update the psychology-center predicate without weakening the current high-risk default.
