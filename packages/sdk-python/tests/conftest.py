# SPDX-License-Identifier: Apache-2.0
"""A scripted local HTTP server: it answers each request with the next scripted response and
records every request, so the client tests run against real sockets and need no Tessary instance."""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit

import pytest


class Recorded:
    def __init__(self, method: str, path: str, query: str, headers: dict[str, str], body: Any) -> None:
        self.method = method
        self.path = path
        self.query = query
        self.headers = headers
        self.body = body


class StubServer:
    def __init__(self) -> None:
        self.responses: list[dict[str, Any]] = []
        self.requests: list[Recorded] = []
        self._lock = threading.Lock()
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                parts = urlsplit(self.path)
                with stub._lock:
                    stub.requests.append(
                        Recorded(
                            self.command,
                            parts.path,
                            parts.query,
                            {k.lower(): v for k, v in self.headers.items()},
                            json.loads(raw) if raw else None,
                        )
                    )
                    reply = stub.responses.pop(0) if stub.responses else {"status": 500, "body": "unscripted"}
                time.sleep(reply.get("delay", 0))
                body = reply.get("body", "")
                payload = body.encode() if isinstance(body, str) else json.dumps(body).encode()
                try:
                    self.send_response(reply["status"])
                    for name, value in reply.get("headers", {}).items():
                        self.send_header(name, value)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except OSError:
                    pass

            do_GET = do_POST = do_PATCH = _handle

            def log_message(self, format: str, *args: Any) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, args=(0.05,), daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        host, port = self._server.server_address[:2]
        return f"http://{host}:{port}"

    def script(self, *responses: dict[str, Any]) -> None:
        self.responses.extend(responses)

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


@pytest.fixture
def server():
    stub = StubServer()
    yield stub
    stub.close()


def envelope(**overrides: Any) -> dict[str, Any]:
    """A PublicPrompt inside the ApiResponse envelope, as GET /v1/prompts/fetch answers it."""
    data: dict[str, Any] = {
        "id": "pv_1",
        "name": "team/a/b",
        "version": 3,
        "type": "text",
        "prompt": "Hello {{ name }}",
        "config": {"temperature": 0.2},
        "labels": ["production", "latest"],
        "tags": ["onboarding"],
        "commitMessage": "warmer tone",
        "createdAt": "2026-10-18T09:12:00Z",
        "updatedAt": "2026-10-18T09:12:00Z",
        "isActive": True,
    }
    data.update(overrides)
    return {"meta": {"status": 200, "success": True}, "data": data}


def api_error(status: int, code: str, message: str) -> dict[str, Any]:
    return {"meta": {"status": status, "success": False, "error": {"code": code, "message": message}}}


class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


class Runner:
    """Holds refresh jobs instead of starting threads, so a test runs them when it chooses."""

    def __init__(self) -> None:
        self.jobs: list[Any] = []

    def __call__(self, job: Any) -> None:
        self.jobs.append(job)

    def run_all(self) -> None:
        jobs, self.jobs = self.jobs, []
        for job in jobs:
            job()
