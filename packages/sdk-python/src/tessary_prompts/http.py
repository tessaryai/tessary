# SPDX-License-Identifier: Apache-2.0
"""The HTTP leg: standard library only, bounded retries, and no redirect ever followed."""

from __future__ import annotations

import http.client
import json
import logging
import random
import urllib.error
import urllib.request
from typing import Any, Callable
from urllib.parse import urlencode

from ._version import __version__
from .errors import PromptApiError, PromptNotFound, PromptTransportError, TessaryPromptsError

_LOG = logging.getLogger("tessary_prompts")
RETRY_AFTER_CAP_SECONDS = 30
JITTER_SECONDS = 0.25

# The prompt a request is about, for PromptNotFound: (name, version, label).
Ref = tuple[str, "int | None", "str | None"]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # Returning None makes urllib raise the 3xx as an HTTPError, so the bearer key is never sent on.
    def redirect_request(self, req: Any, fp: Any, code: Any, msg: Any, headers: Any, newurl: Any) -> None:
        return None


def _error_parts(status: int, raw: bytes, reason: str) -> tuple[str, str]:
    """Code and message from either error shape: the envelope's meta.error, or a filter's error."""
    try:
        doc = json.loads(raw)
        error = (doc.get("meta") or {}).get("error") or doc.get("error")
        return str(error["code"]), str(error["message"])
    except (ValueError, AttributeError, TypeError, KeyError):
        return f"http_{status}", reason


class HttpTransport:
    def __init__(
        self,
        host: str,
        api_key: str,
        timeout: float,
        max_retries: int,
        backoff: float,
        sleep: Callable[[float], None],
        rng: Callable[[], float] = random.random,
    ) -> None:
        self._host = host
        self._api_key = api_key
        self._timeout = timeout
        self._max_retries = max_retries
        self._backoff = backoff
        self._sleep = sleep
        self.rng = rng
        self._opener = urllib.request.build_opener(_NoRedirect)

    def _wait(self, retry_after: str | None) -> float:
        if retry_after and retry_after.isascii() and retry_after.isdigit():
            return float(min(int(retry_after), RETRY_AFTER_CAP_SECONDS))
        return self._backoff + self.rng() * JITTER_SECONDS

    def request(
        self, method: str, path: str, ref: Ref, *, query: dict[str, str] | None = None, body: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        """Send one call with retries and return the envelope's ``data`` object."""
        url = self._host + path + ("?" + urlencode(query) if query else "")
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Accept": "application/json",
            "User-Agent": f"tessary-prompts-python/{__version__}",
        }
        data = None if body is None else json.dumps(body).encode()
        if data is not None:
            headers["Content-Type"] = "application/json"
        is_read = method == "GET"
        attempts = 1 + self._max_retries
        attempt = 0
        while True:
            attempt += 1
            request = urllib.request.Request(url, data=data, headers=headers, method=method)
            error: TessaryPromptsError
            try:
                with self._opener.open(request, timeout=self._timeout) as response:
                    raw = response.read()
            except urllib.error.HTTPError as failure:
                status = failure.code
                raw = failure.read()
                failure.close()
                if status == 404:
                    raise PromptNotFound(_error_parts(status, raw, "not found")[1], name=ref[0], version=ref[1], label=ref[2]) from None
                if 400 <= status < 600:
                    error = PromptApiError(status, *_error_parts(status, raw, str(failure.reason)))
                    retryable = status == 429 or (status >= 500 and is_read)
                else:
                    raise PromptTransportError(f"unexpected HTTP {status}; redirects are not followed") from None
                cause: object = status
                wait = self._wait(failure.headers.get("Retry-After"))
            except (OSError, http.client.HTTPException) as failure:
                error = PromptTransportError(f"{method} {path} failed: {type(failure).__name__}")
                retryable = is_read
                cause = type(failure).__name__
                wait = self._wait(None)
            else:
                return self._data(raw)
            if not retryable or attempt == attempts:
                raise error
            _LOG.debug("retrying %s %s after %s (attempt %d of %d)", method, path, cause, attempt, attempts)
            self._sleep(wait)

    @staticmethod
    def _data(raw: bytes) -> dict[str, Any]:
        try:
            data: dict[str, Any] = json.loads(raw)["data"]
        except (ValueError, TypeError, KeyError) as failure:
            raise PromptTransportError("the server answered 2xx with an unreadable body") from failure
        return data
