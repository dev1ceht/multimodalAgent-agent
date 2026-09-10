#!/usr/bin/env python3
"""Probe real SAA model/tool behavior through the student chat SSE endpoint."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
CASES = Path(__file__).with_name("cases.jsonl")
DEFAULT_OUTPUT = ROOT / "benchmarks" / "results" / "agent-probe" / "model-probe.json"


def json_request(
    url: str,
    *,
    payload: dict[str, Any] | None = None,
    token: str | None = None,
) -> tuple[int, dict[str, Any]]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=body, headers=headers, method="POST"),
            timeout=30,
        ) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exception:
        return exception.code, {}


def login(base_url: str, username: str, password: str) -> str:
    status, payload = json_request(
        f"{base_url.rstrip('/')}/api/auth/login",
        payload={"username": username, "password": password},
    )
    token = payload.get("accessToken")
    if status != 200 or not isinstance(token, str) or not token:
        raise RuntimeError(f"login_failed_http_{status}")
    return token


def grant_consents(base_url: str, token: str) -> None:
    for consent_type in ("PRIVACY_NOTICE", "SENSITIVE_DATA_PROCESSING"):
        status, _ = json_request(
            f"{base_url.rstrip('/')}/api/student/consents",
            token=token,
            payload={"consentType": consent_type, "version": "agent-model-probe-v1"},
        )
        if status not in (200, 201):
            raise RuntimeError(f"consent_failed_http_{status}")


def app_status(base_url: str, token: str) -> dict[str, Any]:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/agent/status",
        headers={"Accept": "application/json", "Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def ollama_metadata(model: str) -> dict[str, Any]:
    """Capture only reproducibility metadata; never persist the model template/body."""
    base_url = os.getenv("OLLAMA_BASE_URL", "http://127.0.0.1:11434").rstrip("/")
    metadata: dict[str, Any] = {"ollamaBaseUrl": base_url}
    try:
        with urllib.request.urlopen(
            urllib.request.Request(f"{base_url}/api/version", method="GET"), timeout=10
        ) as response:
            version = json.loads(response.read().decode("utf-8")).get("version")
            if isinstance(version, str) and version:
                metadata["ollamaVersion"] = version
    except Exception:
        pass

    try:
        with urllib.request.urlopen(
            urllib.request.Request(
                f"{base_url}/api/tags", headers={"Accept": "application/json"}, method="GET"
            ),
            timeout=10,
        ) as response:
            models = json.loads(response.read().decode("utf-8")).get("models", [])
            if isinstance(models, list):
                for item in models:
                    if isinstance(item, dict) and item.get("name") == model:
                        for key in ("digest", "size", "modified_at"):
                            value = item.get(key)
                            if value is not None:
                                metadata[f"model{key.title().replace('_', '')}"] = value
                        break
    except Exception:
        pass

    try:
        body = json.dumps({"name": model}).encode("utf-8")
        with urllib.request.urlopen(
            urllib.request.Request(
                f"{base_url}/api/show",
                data=body,
                headers={"Accept": "application/json", "Content-Type": "application/json"},
                method="POST",
            ),
            timeout=10,
        ) as response:
            payload = json.loads(response.read().decode("utf-8"))
            details = payload.get("details")
            if isinstance(details, dict):
                metadata["modelDetails"] = {
                    key: details[key]
                    for key in ("format", "family", "parameter_size", "quantization_level")
                    if details.get(key) is not None
                }
            template = payload.get("template")
            if isinstance(template, str) and template:
                metadata["templateSha256"] = hashlib.sha256(template.encode("utf-8")).hexdigest()
    except Exception:
        pass
    return metadata
def stream_case(
    base_url: str,
    token: str,
    message: str,
    evaluation_id: str,
) -> dict[str, Any]:
    started = time.perf_counter()
    payload = {"message": message, "evaluationId": evaluation_id}
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/chat/stream",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={
            "Accept": "text/event-stream",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    event_types: list[str] = []
    tool_starts = 0
    tool_results = 0
    token_chars = 0
    done = False
    error = False
    status = 0
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            status = response.status
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                if not line.startswith("data:"):
                    continue
                try:
                    event = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    error = True
                    continue
                kind = event.get("type")
                if not isinstance(kind, str):
                    continue
                event_types.append(kind)
                if kind == "tool_start":
                    tool_starts += 1
                elif kind == "tool_result":
                    tool_results += 1
                elif kind == "token":
                    token_chars += len(str(event.get("content") or ""))
                elif kind == "error":
                    error = True
                elif kind == "done":
                    done = True
    except Exception:
        error = True

    return {
        "httpStatus": status,
        "eventTypes": event_types,
        "toolStarts": tool_starts,
        "toolResults": tool_results,
        "tokenChars": token_chars,
        "done": done,
        "error": error,
        "totalMs": round((time.perf_counter() - started) * 1000, 2),
    }


def case_pass(expected: str, result: dict[str, Any]) -> bool:
    if result["httpStatus"] != 200 or not result["done"]:
        return False
    if expected == "no_tool":
        return result["toolStarts"] == 0 and not result["error"]
    if expected == "multi":
        return result["toolStarts"] >= 2 and result["toolResults"] == result["toolStarts"]
    return result["toolStarts"] >= 1 and result["toolResults"] == result["toolStarts"]


def load_cases() -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in CASES.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("AGENT_USERNAME", "student"))
    parser.add_argument("--password", default=os.getenv("AGENT_PASSWORD", "student123"))
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--grant-consent", action="store_true")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    if args.repetitions < 1 or args.limit < 1:
        raise SystemExit("--repetitions and --limit must be positive")

    token = login(args.base_url, args.username, args.password)
    status = app_status(args.base_url, token)
    if status.get("executionMode") != "saa" or not status.get("realModelEnabled"):
        raise RuntimeError("real_saa_model_required")
    if args.grant_consent:
        grant_consents(args.base_url, token)

    rows = []
    for case in load_cases()[: args.limit]:
        for repetition in range(1, args.repetitions + 1):
            result = stream_case(
                args.base_url,
                token,
                case["message"],
                f"agent-probe-{case['id']}-{repetition}",
            )
            row = {
                "caseId": case["id"],
                "expected": case["expected"],
                "repetition": repetition,
                "result": result,
                "passed": case_pass(case["expected"], result),
            }
            rows.append(row)
            print(json.dumps({"case": case["id"], "repetition": repetition, "passed": row["passed"]}, ensure_ascii=False))

    expected_tool = [row for row in rows if row["expected"] in ("tool", "multi")]
    no_tool = [row for row in rows if row["expected"] == "no_tool"]
    multi = [row for row in rows if row["expected"] == "multi"]
    safe_repetition_cap = all(row["result"]["toolStarts"] <= 8 for row in rows)
    admission = {
        "passed": False,
        "toolNameParameterLegalityRate": None,
        "toolNameParameterLegalityThreshold": 0.95,
        "toolNameParameterLegalityMeasured": False,
        "multiStepSuccessRate": sum(row["passed"] for row in multi) / max(1, len(multi)),
        "multiStepSuccessThreshold": 0.90,
        "safeRepetitionCap": safe_repetition_cap,
        "reason": "公开 SSE 刻意不包含工具参数，当前探针只能判定工具事件形状；需通过受控 run 元数据补充名称/参数合法率。",
    }
    output = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "executionMode": status.get("executionMode"),
        "model": status.get("agentModel") or status.get("model"),
        "toolCallingVerified": status.get("toolCallingVerified"),
        "generation": status.get("generation", {}),
        "ollama": ollama_metadata(status.get("agentModel") or status.get("model") or ""),
        "caseCount": len(rows),
        "observedToolCasePassRate": sum(row["passed"] for row in expected_tool) / max(1, len(expected_tool)),
        "noToolPassRate": sum(row["passed"] for row in no_tool) / max(1, len(no_tool)),
        "multiStepObservedPassRate": sum(row["passed"] for row in multi) / max(1, len(multi)),
        "safeRepetitionCap": safe_repetition_cap,
        "admission": admission,
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "caseCount": len(rows), "safeRepetitionCap": safe_repetition_cap, "admissionPassed": admission["passed"]}, ensure_ascii=False))
    return 0 if admission["passed"] else 2


if __name__ == "__main__":
    sys.exit(main())
