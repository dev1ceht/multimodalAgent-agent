# Centralized logs and distributed traces runbook

## Purpose

This runbook covers the local Loki, Tempo, Alloy, and Grafana flow. Application logs include
`traceId` and `spanId`; Alloy reads the shared log volume and sends entries to Loki; the application
exports sampled spans to Tempo over OTLP HTTP. Grafana links matching logs and traces.

## Start the stack

Tracing is intentionally opt-in. In PowerShell:

```powershell
$env:TRACING_ENABLED = "true"
$env:TRACING_SAMPLING_PROBABILITY = "1.0"
$env:GRAFANA_ADMIN_PASSWORD = "replace-with-a-long-random-value"
docker compose --profile observability up --build -d
```

Use a probability of `1.0` only for local verification. The normal configured value is `0.1`.

Local interfaces:

- Grafana: <http://localhost:3000>
- Loki readiness: <http://localhost:3100/ready>
- Tempo readiness: <http://localhost:3200/ready>

OTLP ports `4317` and `4318` are available only inside the Compose network.

## Verify log-to-trace navigation

1. Wait for the application, Loki, Tempo, and Alloy containers to become ready.
2. Exercise an authenticated API route that makes a downstream HTTP call.
3. In Grafana **Explore**, select Loki and run:

   ```logql
   {service_name="multimodalAgent-agent", environment="local-compose"}
   ```

4. Open a recent line and confirm it contains 32-character `traceId` and 16-character `spanId`
   values rather than `none`.
5. Select the `TraceID` derived-field link. Grafana should open the corresponding Tempo trace and
   show the server span plus any instrumented downstream HTTP spans.
6. From the Tempo trace, use the configured logs link to return to the surrounding Loki entries.

If logs are absent, inspect `docker compose logs alloy` and confirm the `app-logs` volume is mounted
at `/var/log/multimodalagent`. If traces are absent, confirm `TRACING_ENABLED=true`, inspect the app
for OTLP export errors, and verify that its endpoint is `http://tempo:4318/v1/traces`.

## Validate configuration

CI runs the following checks with the pinned images:

```powershell
docker run --rm -v "${PWD}/observability/loki:/etc/loki:ro" grafana/loki:3.7.3 -config.file=/etc/loki/loki.yml -verify-config=true
docker run --rm -v "${PWD}/observability/tempo:/etc/tempo:ro" grafana/tempo:2.10.7 --config.file=/etc/tempo/tempo.yml --config.verify
docker run --rm -v "${PWD}/observability/alloy:/etc/alloy:ro" grafana/alloy:v1.18.0 validate /etc/alloy/config.alloy
docker compose --profile observability config --quiet
```

## Production boundaries

- Keep Grafana, Loki, Tempo, and OTLP endpoints on private networks behind authenticated TLS.
- Replace local filesystem storage with approved durable object storage and define backup and
  restore procedures.
- Set retention from legal, privacy, and incident-response requirements; seven days is only the
  local default.
- Do not log prompts, retrieved chunks, uploaded media, document contents, tokens, credentials, or
  student-support notes. Redact sensitive query parameters and headers before ingestion.
- Never turn `traceId`, `spanId`, request IDs, user IDs, document IDs, routes containing IDs, or raw
  error messages into Loki labels.
- Restrict Explore and trace access, audit administrative actions, and test deletion procedures.
- Adjust sampling based on traffic and risk; temporary 100-percent sampling requires approval and
  a defined rollback time.
