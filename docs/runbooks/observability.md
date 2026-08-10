# Observability and alert response runbook

## Purpose

This runbook covers the local Prometheus, Alertmanager, and Grafana stack for
multimodalAgent. It monitors application availability, normalized HTTP behavior, RAG retrieval,
evidence rejection, and knowledge-index failures without adding user text, document names, raw
exceptions, or identifiers as metric labels.

The application serves business traffic on port `8080`. Actuator health and metrics default to
`127.0.0.1:9090`. Docker Compose explicitly changes the management address to `0.0.0.0` inside the
application container, exposes that port only to the internal network, and does not publish it on
the host. Prometheus is the only expected scraper in this profile. Monitoring UIs are published on
the host loopback interface only.

## Start the stack

Set a non-default Grafana password before starting any shared environment. In PowerShell:

```powershell
$env:GRAFANA_ADMIN_PASSWORD = "replace-with-a-long-random-value"
docker compose --profile observability up --build -d
```

Local interfaces:

- Grafana: <http://localhost:3000> (`admin` and the configured password)
- Prometheus: <http://localhost:9090>
- Alertmanager: <http://localhost:9093>
- Mailpit alert inbox: <http://localhost:8025>

The provisioned Grafana folder `multimodalAgent` contains the operations overview dashboard. It
shows scrape availability, HTTP 5xx ratio, HTTP p95 latency, RAG outcomes, and index outcomes.

## Verify a deployment

1. Open Prometheus **Status > Target health** and verify job `multimodalagent` is `UP`.
2. Query `up{job="multimodalagent"}` and confirm the value is `1`.
3. Open **Alerts** and confirm all multimodalAgent rules are loaded and inactive.
4. Open Grafana and confirm the provisioned Prometheus data source reports success.
5. Exercise one authenticated API route and confirm HTTP request data appears after the next
   15-second scrape.

Liveness and readiness are available to the internal network at
`/actuator/health/liveness` and `/actuator/health/readiness`. Health responses never expose details.

## Alert drill

Use this only in a local or explicitly approved non-production environment:

```powershell
docker compose stop app
```

After the two-minute `for` period and Alertmanager group wait, `MultimodalAgentUnavailable` should
be firing in Prometheus and Alertmanager, and an email should appear at <http://localhost:8025>.
Restore the service and verify the resolved notification:

```powershell
docker compose start app
```

If the drill does not notify, check Prometheus target health, Prometheus rule state, Alertmanager
status, and Mailpit logs in that order.

## Alert ownership and first response

| Alert | Severity | First response |
|---|---|---|
| `MultimodalAgentUnavailable` | critical | Check application startup, database/Redis/Elasticsearch reachability, then recent deploy logs. |
| `HighHttpServerErrorRatio` | critical | Identify affected normalized routes, correlate with dependency failures, and consider rollback. |
| `HighHttpRequestLatency` | warning | Compare request and RAG latency, then inspect model and vector-store saturation. |
| `RagRetrievalFailures` | critical | Check Elasticsearch and embedding availability; do not silently enable a fallback for required retrieval. |
| `RagEvidenceRejectionSpike` | warning | Check active knowledge version, indexing completeness, and retrieval/rerank configuration. |
| `KnowledgeIndexFailures` | warning | Inspect failed or lease-lost tasks and retry pressure before publishing a knowledge version. |
| `KnowledgeIndexRetryPressure` | warning | Check embedding/vector-store health and whether retry queues are draining. |

Critical alerts require immediate acknowledgement by the on-call owner. Warning alerts should be
triaged during the active support window. Alert resolution means the technical signal recovered; it
does not close any student risk case or substitute for human safety follow-up.

## Validate configuration

CI validates both configuration files with the binaries shipped in the pinned container images.
The equivalent local checks are:

```powershell
docker run --rm --entrypoint=/bin/promtool -v "${PWD}/observability/prometheus:/etc/prometheus:ro" prom/prometheus:v3.13.1 check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint=/bin/amtool -v "${PWD}/observability/alertmanager:/etc/alertmanager:ro" prom/alertmanager:v0.33.1 check-config /etc/alertmanager/alertmanager.yml
```

## Production boundaries

The Compose profile is a reproducible development and acceptance environment, not a complete
production topology. Before production rollout:

- keep the application management port on a private monitoring network; never publish it directly
  to the internet;
- terminate TLS and require SSO or equivalent access control for Grafana, Prometheus, and
  Alertmanager;
- inject the Grafana admin password through the platform secret store and rotate the bootstrap
  credential after provisioning;
- replace the Mailpit receiver with the approved on-call receiver and test both firing and resolved
  notifications;
- set production environment labels, durable retention, capacity limits, backups, and availability
  appropriate to the deployment platform;
- preserve bounded metric dimensions and review every new label for privacy and cardinality risk;
- run the alert drill after receiver, routing, or threshold changes and record the result in the
  deployment evidence.

Centralized logs and distributed traces are covered by
[`logs-and-traces.md`](logs-and-traces.md). Use that runbook to move from this stack's alert or
metric time window into correlated application logs and Tempo spans.
