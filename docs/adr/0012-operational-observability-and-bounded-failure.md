# Establish operational observability and bounded failure dimensions

- Status: Accepted
- Date: 2026-08-07

## Context

The RAG and knowledge-index workflows already expose useful evaluation traces, but evaluation traces are not a runtime monitoring contract. Operators need to distinguish dependency failures, invalid data, configuration errors, retryable index failures, lease loss, and evidence-quality rejection without putting raw user input or exception messages into metric labels.

Outbound HTTP calls also need one shared connection and response timeout policy. Request failures must be traceable across reactive processing without trusting an unbounded client-supplied identifier.

## Decision

Introduce `OperationalMetrics` as the application-owned metrics seam. It records bounded tags only:

- `multimodalagent.rag.retrieval`: `backend`, `status`, `reason`, and retrieval latency.
- `multimodalagent.rag.evidence.quality`: `accepted` counter.
- `multimodalagent.knowledge.index`: `outcome`, `reason`, and task latency.
- `multimodalagent.http.request`: normalized `method`, route template, status, and request latency.

Reason tags are classified into a small fixed vocabulary. Raw exception messages remain in existing task/trace fields where appropriate, but never become metric dimensions. Index processing reports `lease_lost` separately from `succeeded` so a worker that loses ownership cannot create a false success signal.

`RequestCorrelationWebFilter` accepts only `[A-Za-z0-9._-]{1,64}` request IDs. Invalid or absent IDs are replaced with a UUID, returned as `X-Request-Id`, and placed in Reactor context. HTTP metrics use the Spring route template when available and use `unmatched` otherwise; raw request paths are not used as labels.

Actuator exposes `health` publicly, while `info` and `metrics` require the `ADMIN` role. All shared WebClient users receive configurable connection and response timeouts through `multimodal-agent.resilience`.

## Consequences

Operators can alert on retrieval dependency failures, evidence-quality rejection, index retry pressure, lease loss, and HTTP latency without parsing application logs. The metrics endpoint is useful for authenticated dashboards while health probes remain available to orchestrators.

The fixed reason vocabulary intentionally loses detailed error text in metric dimensions. Detailed diagnosis still requires the request ID, evaluation trace, task error, or application log. Timeout values are shared defaults and may be overridden with `HTTP_CONNECT_TIMEOUT_MS` and `HTTP_RESPONSE_TIMEOUT_MS` for deployment-specific model or embedding latency.
