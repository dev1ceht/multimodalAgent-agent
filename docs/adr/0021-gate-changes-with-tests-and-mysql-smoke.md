# ADR 0021: Gate changes with tests and a real MySQL migration smoke

## Status

Accepted

## Context

The project has a complete local test suite and a reproducible Flyway smoke script, but neither protects a shared branch unless every change runs them automatically. The application also uses H2 for most tests, so a green unit/integration suite alone cannot prove that the MySQL profile starts with `ddl-auto=validate` or that a fresh database applies `V0` through `V4` correctly.

The migration smoke must remain diagnosable and disposable. It must not use production credentials, preserve a database volume, or leave containers running after a failed job.

## Decision

1. Run GitHub Actions on pushes, pull requests, and manual dispatch with repository contents read-only permission and concurrency cancellation for superseded runs.
2. Make `Java tests` and `MySQL migration smoke` independent required checks. The first runs the full Maven suite on Java 17. The second runs the PowerShell smoke script on an Ubuntu runner against an ephemeral MySQL 8.4 container.
3. Keep the smoke script runnable on both Windows PowerShell and PowerShell 7 on Linux. Windows-only process options are applied only on Windows, and repository paths are constructed without platform-specific separators.
4. Upload Surefire reports or application smoke logs only on failure, retain them for seven days, and never put production credentials or user data in those artifacts.
5. Keep deployment outside this workflow. Branch protection is responsible for requiring both checks before merge. The fresh-database smoke does not replace a staging rehearsal of the version-1 baseline path for an existing non-empty database.

## Consequences

- Every proposed change gets the same Java and MySQL migration gates instead of relying on a developer's local environment.
- MySQL-specific schema drift fails before merge, with bounded logs available for diagnosis and automatic container cleanup.
- The workflow adds one containerized job and an operating-system package install, increasing CI time and depending on GitHub-hosted runner and package-registry availability.
- Repository administrators must configure required checks once; this repository change cannot enforce branch-protection settings by itself.
