# SPDX-License-Identifier: Apache-2.0
"""The three failures a caller can tell apart, under one base class."""

from __future__ import annotations


class TessaryPromptsError(Exception):
    """Base class: catch this to handle every SDK failure."""


class PromptNotFound(TessaryPromptsError):
    """The server answered 404: no prompt with that name, version, or label."""

    def __init__(
        self, message: str, *, name: str | None, version: int | None = None, label: str | None = None
    ) -> None:
        super().__init__(message)
        self.name = name
        self.version = version
        self.label = label


class PromptApiError(TessaryPromptsError):
    """The server refused the request: any non-2xx answer other than 404 and a redirect."""

    def __init__(self, status: int, code: str, message: str) -> None:
        super().__init__(f"{status} {code}: {message}")
        self.status = status
        self.code = code
        self.message = message


class PromptTransportError(TessaryPromptsError):
    """No usable answer: a timeout, a connection failure, a redirect, or an unreadable body."""
