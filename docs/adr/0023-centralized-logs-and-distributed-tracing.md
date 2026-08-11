# ADR 0023: Centralized logs and distributed tracing

Status: Accepted

Date: 2026-08-09

## Context

ADR 0022 established metrics, alerts, and dashboards, but incident diagnosis still required reading
one application log file and manually correlating time windows. Calls to model, embedding, Elasticsearch,
and MCP HTTP services also crossed process boundaries without an application-owned propagation
contract. This makes latency and dependency failures difficult to localize.

Logs and traces may contain sensitive student-support metadata. Their indexes must use bounded
dimensions, retain data for an explicit period, and avoid promoting request IDs, trace IDs, user
input, document names, exception messages, or business identifiers to labels.

## Decision

1. Use Micrometer Tracing with the OpenTelemetry bridge and the OTLP HTTP exporter. Tracing is
   configurable and disabled by default; the observability runbook enables it explicitly alongside
   Tempo. The default sample rate when enabled is 10 percent.
2. Use W3C propagation. The shared Boot-managed `WebClient.Builder` retains Spring observations,
   central timeout policy, and native Micrometer propagation so every HTTP integration follows one
   outbound contract. Supply the W3C `ContextPropagators` bean explicitly to avoid an empty
   propagator during auto-configuration in the current dependency set. Disable tracing baggage
   because this system does not propagate business context in headers and the narrower contract
   avoids unnecessary cross-service metadata.
3. Put `traceId` and `spanId` in the logging correlation pattern and enable automatic Reactor
   context propagation. The application can write the same structured correlation fields to stdout
   and to a configurable file.
4. Add pinned Loki, Tempo, and Alloy services to the opt-in Compose `observability` profile. Alloy
   tails the application log volume and adds only the bounded `service_name` and `environment`
   labels. Tempo accepts OTLP HTTP and gRPC internally.
5. Retain local logs and traces for seven days. Loki and Tempo use local single-node storage only
   for development and acceptance testing.
6. Provision Loki and Tempo as Grafana data sources. A Loki derived field opens the matching Tempo
   trace, and Tempo can query the corresponding Loki time window by trace ID.
7. Bind Loki and Tempo query ports to host loopback. Do not publish OTLP ingestion ports on the
   host. CI validates Loki, Tempo, and Alloy configuration with the pinned binaries.

## Consequences

- Operators can move from an alert to a log line and then to the exact dependency span without
  searching across unrelated requests.
- Trace IDs remain log content rather than Loki labels, avoiding unbounded index cardinality.
- Tracing has runtime and storage cost, controlled through enablement, sampling, and retention.
- Production still requires authenticated TLS ingress, redaction policy, approved retention,
  durable object storage, capacity planning, backups, and access audit controls.
- This decision completes the centralized-log and distributed-trace work identified as future scope
  in ADR 0022; it does not make the local Compose topology production-high-availability.
