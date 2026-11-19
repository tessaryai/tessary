# SPDX-License-Identifier: Apache-2.0
"""Automatic span linking through OpenTelemetry context. Needs only ``opentelemetry-api`` (the ``otel``
extra); the package's main entry never imports this module.

``with prompt.linked():`` puts the prompt under a private context key, not baggage, so nothing leaves
the process. ``PromptLinkSpanProcessor``, added once to your tracer provider, copies it onto every span
started inside the block, including the model-call spans an auto-instrumented client starts."""

from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Iterator

from opentelemetry import context, trace

_LINK_KEY = context.create_key("tessary.prompt.link")


def current_span() -> Any:
    return trace.get_current_span()


@contextmanager
def linked_context(attributes: dict[str, str]) -> Iterator[None]:
    """Run the block with ``attributes`` as the link; an empty dict (a fallback) masks an outer link."""
    token = context.attach(context.set_value(_LINK_KEY, attributes))
    try:
        yield
    finally:
        context.detach(token)


class PromptLinkSpanProcessor:
    """Sets ``tessary.prompt.name`` and ``tessary.prompt.version`` on each span started inside a
    ``prompt.linked()`` block. Duck-typed to the SDK's span processor, so this package needs only the
    OpenTelemetry API: ``provider.add_span_processor(PromptLinkSpanProcessor())``."""

    def on_start(self, span: Any, parent_context: Any = None) -> None:
        # The SDK passes None when the span was started without an explicit context: get_value then
        # reads the current one.
        attributes = context.get_value(_LINK_KEY, parent_context)
        if attributes:
            for key, value in attributes.items():  # type: ignore[attr-defined]
                span.set_attribute(key, value)

    def _on_ending(self, span: Any) -> None:
        pass

    def on_end(self, span: Any) -> None:
        pass

    def shutdown(self) -> None:
        pass

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True
