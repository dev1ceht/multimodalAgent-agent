#!/usr/bin/env python3
"""Run a privacy-safe MCP/Agent capability probe against a local application."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "benchmarks" / "results" / "agent-probe" / "probe.json"
ALLOWED_TOOL = "get_support_status"
SAFE_CODE = re.compile(r"[a-z][a-z0-9_]{1,63}")


def request_json(
    url: str,
    *,
    method: str = "POST",
    payload: dict[str, Any] | None = None,
    token: str | None = None,
) -> tuple[int, dict[str, Any]]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exception:
        raw = exception.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            payload = {}
        return exception.code, payload if isinstance(payload, dict) else {}


def login(base_url: str, username: str, password: str) -> str:
    status, payload = request_json(
        f"{base_url.rstrip('/')}/api/auth/login",
        payload={"username": username, "password": password},
    )
    token = payload.get("accessToken")
    if status != 200 or not isinstance(token, str) or not token:
        raise RuntimeError(f"login_failed_http_{status}")
    return token


def rpc(
    base_url: str,
    token: str | None,
    request_id: int,
    method: str,
    params: dict[str, Any] | None = None,
) -> tuple[int, dict[str, Any]]:
    return request_json(
        f"{base_url.rstrip('/')}/agent-mcp",
        token=token,
        payload={
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params or {},
        },
    )


def notification(base_url: str, token: str, method: str) -> tuple[int, dict[str, Any]]:
    return request_json(
        f"{base_url.rstrip('/')}/agent-mcp",
        token=token,
        payload={"jsonrpc": "2.0", "method": method, "params": {}},
    )


def result_text_code(response: dict[str, Any]) -> str | None:
    result = response.get("result")
    if not isinstance(result, dict):
        return None
    content = result.get("content")
    if not isinstance(content, list):
        return None
    for item in content:
        if not isinstance(item, dict) or item.get("type") != "text":
            continue
        text = item.get("text")
        if not isinstance(text, str):
            continue
        try:
            parsed = json.loads(text)
            if isinstance(parsed, dict) and isinstance(parsed.get("errorCode"), str):
                candidate = parsed["errorCode"].strip().lower()
                return candidate if SAFE_CODE.fullmatch(candidate) else "redacted"
        except json.JSONDecodeError:
            candidate = text.strip().lower()
            if SAFE_CODE.fullmatch(candidate):
                return candidate
    return None


def safe_result(response: dict[str, Any]) -> dict[str, Any]:
    result = response.get("result")
    return {
        "isError": bool(result.get("isError")) if isinstance(result, dict) else None,
        "textCode": result_text_code(response),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--username", default=os.getenv("AGENT_USERNAME", "student"))
    parser.add_argument("--password", default=os.getenv("AGENT_PASSWORD", "student123"))
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--grant-consent", action="store_true")
    parser.add_argument("--require-tool-success", action="store_true")
    args = parser.parse_args()

    token = login(args.base_url, args.username, args.password)
    cases: list[dict[str, Any]] = []

    unauth_status, _ = rpc(args.base_url, None, 1, "initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "mindcare-agent-probe", "version": "1.0"},
    })
    cases.append({"id": "unauthenticated", "httpStatus": unauth_status, "passed": unauth_status == 401})

    if args.grant_consent:
        for consent_type in ("PRIVACY_NOTICE", "SENSITIVE_DATA_PROCESSING"):
            status, _ = request_json(
                f"{args.base_url.rstrip('/')}/api/student/consents",
                token=token,
                payload={"consentType": consent_type, "version": "agent-probe-v1"},
            )
            cases.append({
                "id": f"grant_{consent_type.lower()}",
                "httpStatus": status,
                "passed": status in (200, 201),
            })

    init_status, init_response = rpc(args.base_url, token, 2, "initialize", {
        "protocolVersion": "2025-03-26",
        "capabilities": {},
        "clientInfo": {"name": "mindcare-agent-probe", "version": "1.0"},
    })
    init_result = init_response.get("result") if isinstance(init_response, dict) else {}
    cases.append({
        "id": "initialize",
        "httpStatus": init_status,
        "protocolVersion": init_result.get("protocolVersion") if isinstance(init_result, dict) else None,
        "passed": init_status == 200 and isinstance(init_result, dict)
        and init_result.get("protocolVersion") == "2025-03-26",
    })

    initialized_status, _ = notification(args.base_url, token, "notifications/initialized")
    cases.append({"id": "initialized", "httpStatus": initialized_status, "passed": initialized_status == 200})

    tools_status, tools_response = rpc(args.base_url, token, 3, "tools/list")
    tools_result = tools_response.get("result") if isinstance(tools_response, dict) else {}
    tools = tools_result.get("tools") if isinstance(tools_result, dict) else []
    names = [item.get("name") for item in tools if isinstance(item, dict)] if isinstance(tools, list) else []
    schema_ok = (
        isinstance(tools, list)
        and len(tools) == 1
        and isinstance(tools[0], dict)
        and tools[0].get("name") == ALLOWED_TOOL
        and tools[0].get("inputSchema", {}).get("additionalProperties") is False
    )
    cases.append({
        "id": "tools_list_allowlist",
        "httpStatus": tools_status,
        "toolNames": names,
        "schemaAdditionalPropertiesFalse": schema_ok,
        "passed": tools_status == 200 and schema_ok,
    })

    invalid_status, invalid_response = rpc(args.base_url, token, 4, "tools/call", {
        "name": ALLOWED_TOOL,
        "arguments": {"userId": 999999, "consentValidated": True},
    })
    invalid_result = safe_result(invalid_response)
    cases.append({
        "id": "unknown_argument_rejected",
        "httpStatus": invalid_status,
        **invalid_result,
        "passed": invalid_status == 200 and invalid_result["isError"] is True,
    })

    call_status, call_response = rpc(args.base_url, token, 5, "tools/call", {
        "name": ALLOWED_TOOL,
        "arguments": {},
    })
    call_result = safe_result(call_response)
    call_passed = call_status == 200 and call_result["isError"] is False
    cases.append({
        "id": "student_support_status_call",
        "httpStatus": call_status,
        **call_result,
        "passed": call_passed,
    })

    args.output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "usernameRole": "student-probe",
        "cases": cases,
        "passed": all(case.get("passed") for case in cases),
    }
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(args.output), "passed": payload["passed"]}, ensure_ascii=False))
    if args.require_tool_success and not call_passed:
        return 2
    return 0 if payload["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
