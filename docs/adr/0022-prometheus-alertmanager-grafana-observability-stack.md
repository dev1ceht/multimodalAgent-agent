# ADR 0022: Prometheus, Alertmanager, and Grafana observability stack

Status: Accepted

Date: 2026-08-09

## Context

The application already records bounded operational metrics for HTTP requests, RAG retrieval,
evidence quality, and knowledge indexing. Those metrics were available only through the in-process
Micrometer registry, so operators had no durable time series, dashboards, evaluated alert rules, or
notification routing. Actuator metrics also shared the business listener, which is not an adequate
production security boundary.

This project handles sensitive student-support workflows. Metric labels must stay bounded and must
not include user input, document names, identifiers, exception messages, or raw routes containing
IDs. Operational alert resolution must remain separate from the lifecycle of a human risk case.

## Decision

1. Add the Micrometer Prometheus registry and expose `/actuator/prometheus` together with liveness
   and readiness probes.
2. By default, bind Actuator to the loopback-only management port and listener `127.0.0.1:9090` while
   business traffic remains on `8081`. In the Compose deployment, explicitly bind the management
   listener to the container network while leaving it unpublished on the host.
3. Add an opt-in `observability` profile containing pinned Prometheus, Alertmanager, and Grafana
   images. Prometheus scrapes the application management listener and evaluates version-controlled
   alert rules.
4. Route local Alertmanager email notifications to Mailpit so firing and resolved alerts can be
   tested end to end without contacting people. Production deployments must replace this receiver
   with an approved on-call integration.
5. Provision the Prometheus data source and the multimodalAgent Grafana dashboard from source
   control. CI validates Prometheus rules/configuration and Alertmanager configuration using the
   binaries from the pinned images.
6. Alert only on bounded operational signals. Alerts describe system health; they never close,
   downgrade, or acknowledge a student risk case.
7. Bind the local Prometheus, Alertmanager, and Grafana host ports to `127.0.0.1`. Production access
   requires an authenticated and encrypted ingress rather than widening these development binds.

This decision refines ADR 0012's endpoint policy. `/actuator/info` and `/actuator/metrics` continue
to require the `ADMIN` role. `/actuator/prometheus` is intentionally unauthenticated for scraper
compatibility, but is safe only behind the dedicated loopback/private management-listener boundary;
it must not be exposed through the public business ingress.

## Consequences

- A developer can start one reproducible stack and verify scrape, rule evaluation, notification,
  and visualization behavior.
- RAG and indexing degradation become visible before they appear only as user complaints.
- The Compose profile intentionally remains single-node and local. Production still requires
  private networking, TLS/SSO, secret management, durable storage, an approved receiver, and
  platform-specific availability design.
- Centralized logs and distributed traces remain separate future decisions; correlation IDs are
  used to pivot from metric time windows to current application logs.
