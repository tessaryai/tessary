# SPDX-License-Identifier: Apache-2.0
"""Prompt values: compile a template, list its variables, and link a span to the version."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, ClassVar

# The one variable syntax. The backend, the frontend, and the TypeScript SDK hold the same
# literal, and a repo gate compares the copies.
VARIABLE_PATTERN = re.compile(r"\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}")


def _render(template: str, variables: dict[str, Any]) -> str:
    def replace(match: re.Match[str]) -> str:
        name = match.group(1)
        if name not in variables:
            return match.group(0)
        value = variables[name]
        return "" if value is None else str(value)

    return VARIABLE_PATTERN.sub(replace, template)


def _scan(template: str, seen: list[str]) -> None:
    for match in VARIABLE_PATTERN.finditer(template):
        if match.group(1) not in seen:
            seen.append(match.group(1))


@dataclass
class BasePrompt:
    """A fetched prompt version, or a fallback when ``is_fallback`` is true."""

    type: ClassVar[str]
    name: str
    version: int
    prompt: Any
    config: dict[str, Any] = field(default_factory=dict)
    labels: list[str] = field(default_factory=list)
    tags: list[str] = field(default_factory=list)
    commit_message: str | None = None
    is_fallback: bool = False

    def variables(self) -> list[str]:
        """Variable names in first-seen order, each once."""
        seen: list[str] = []
        for template in self._templates():  # type: ignore[attr-defined]  # each subclass defines it
            _scan(template, seen)
        return seen

    def span_attributes(self) -> dict[str, str]:
        """The span attributes that link a call to this version; empty for a fallback."""
        if self.is_fallback:
            return {}
        return {"tessary.prompt.name": self.name, "tessary.prompt.version": str(self.version)}

    def link(self, span: Any) -> Any:
        """Set the link attributes on any object with ``set_attribute``; return the span."""
        setter = getattr(span, "set_attribute", None)
        if callable(setter):
            for key, value in self.span_attributes().items():
                setter(key, value)
        return span

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "type": self.type,
            "prompt": self.prompt,
            "config": self.config,
            "labels": self.labels,
            "tags": self.tags,
            "commitMessage": self.commit_message,
            "isFallback": self.is_fallback,
        }


@dataclass
class TextPrompt(BasePrompt):
    type: ClassVar[str] = "text"
    prompt: str = ""

    def _templates(self) -> list[str]:
        return [self.prompt]

    def compile(self, /, **variables: Any) -> str:
        return _render(self.prompt, variables)


def _message(item: Any, variables: dict[str, Any]) -> dict[str, Any]:
    if not (isinstance(item, dict) and "role" in item and "content" in item):
        raise TypeError("each placeholder item must be a dict with role and content")
    return {**item, "content": _render(item["content"], variables)}


def _is_placeholder(item: dict[str, Any]) -> bool:
    return item.get("type") == "placeholder"


@dataclass
class ChatPrompt(BasePrompt):
    type: ClassVar[str] = "chat"
    prompt: list[dict[str, Any]] = field(default_factory=list)

    def _templates(self) -> list[str]:
        return [item["content"] for item in self.prompt if not _is_placeholder(item)]

    def compile(self, /, **variables: Any) -> list[dict[str, Any]]:
        """Compile every message; a list-valued variable fills the placeholder of that name."""
        out: list[dict[str, Any]] = []
        for item in self.prompt:
            if not _is_placeholder(item):
                out.append(_message(item, variables))
                continue
            fill = variables.get(item["name"])
            if isinstance(fill, list):
                out.extend(_message(entry, variables) for entry in fill)
            else:
                out.append(dict(item))
        return out


def from_data(data: dict[str, Any]) -> TextPrompt | ChatPrompt:
    """Build a prompt from the ``data`` of a PublicPrompt envelope."""
    cls = ChatPrompt if data["type"] == "chat" else TextPrompt
    return cls(
        name=data["name"],
        version=data["version"],
        prompt=data["prompt"],
        config=data.get("config") or {},
        labels=list(data.get("labels") or []),
        tags=list(data.get("tags") or []),
        commit_message=data.get("commitMessage"),
    )


def fallback_prompt(name: str, value: str | list[dict[str, Any]]) -> TextPrompt | ChatPrompt:
    """A prompt built from the caller's own text: version 0, never cached, never linked."""
    cls = TextPrompt if isinstance(value, str) else ChatPrompt
    return cls(name=name, version=0, prompt=value, is_fallback=True)
