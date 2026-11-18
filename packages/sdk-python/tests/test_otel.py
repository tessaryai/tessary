# SPDX-License-Identifier: Apache-2.0
"""Automatic linking through OpenTelemetry context, on a real tracer provider. The gate runs this file
against the newest OpenTelemetry SDK and against the oldest one the otel extra allows."""

import sys

import pytest
from opentelemetry import context
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from tessary_prompts import ChatPrompt, TextPrompt
from tessary_prompts.otel import PromptLinkSpanProcessor

LINK = ("tessary.prompt.name", "tessary.prompt.version")


@pytest.fixture
def otel():
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(PromptLinkSpanProcessor())
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    yield provider.get_tracer("test"), exporter
    provider.shutdown()


def link_of(exporter, name):
    (span,) = [s for s in exporter.get_finished_spans() if s.name == name]
    return {k: v for k, v in (span.attributes or {}).items() if k in LINK}


def prompt(name="a", version=3, **kwargs):
    return TextPrompt(name=name, version=version, prompt="p", **kwargs)


def test_span_started_inside_the_block_carries_the_link(otel):
    # Bug: the review's main adoption gap; a model-call span has no link unless the user calls link(span) by hand.
    tracer, exporter = otel
    with prompt().linked():
        with tracer.start_as_current_span("agent"):
            tracer.start_span("llm").end()
    assert link_of(exporter, "llm") == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}
    assert link_of(exporter, "agent") == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}


def test_span_started_after_the_block_carries_no_link(otel):
    # Bug: the context leaks and later calls are counted under the wrong prompt.
    tracer, exporter = otel
    with prompt().linked():
        pass
    tracer.start_span("llm").end()
    assert link_of(exporter, "llm") == {}


def test_nested_blocks_link_the_inner_prompt(otel):
    # Bug: the outer prompt wins, or leaving the inner block drops the outer link.
    tracer, exporter = otel
    with prompt("outer", 1).linked():
        with ChatPrompt(name="inner", version=2, prompt=[]).linked():
            tracer.start_span("inner-call").end()
        tracer.start_span("outer-call").end()
    assert link_of(exporter, "inner-call") == {"tessary.prompt.name": "inner", "tessary.prompt.version": "2"}
    assert link_of(exporter, "outer-call") == {"tessary.prompt.name": "outer", "tessary.prompt.version": "1"}


def test_fallback_prompt_links_nothing(otel):
    # Bug: usage is counted for version 0, or a fallback inside a linked block is counted under the outer prompt.
    tracer, exporter = otel
    with prompt(version=0, is_fallback=True).linked():
        tracer.start_span("alone").end()
    with prompt("outer", 1).linked():
        with prompt("inner", 0, is_fallback=True).linked():
            tracer.start_span("nested").end()
            with tracer.start_as_current_span("active") as span:
                assert prompt("inner", 0, is_fallback=True).link() is span
    assert link_of(exporter, "alone") == {}
    assert link_of(exporter, "nested") == {}
    assert link_of(exporter, "active") == {}


def test_link_without_a_span_links_the_active_span(otel):
    # Bug: link() with no argument needs the caller to find the current span by hand, or links a parent.
    tracer, exporter = otel
    with tracer.start_as_current_span("outer"):
        with tracer.start_as_current_span("call") as span:
            assert prompt().link() is span
    assert link_of(exporter, "call") == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}
    assert link_of(exporter, "outer") == {}


def test_an_explicit_parent_context_decides_the_link(otel):
    # Bug: the processor reads the ambient context instead of the context the span was started with.
    tracer, exporter = otel
    with prompt().linked():
        inside = context.get_current()
        tracer.start_span("detached", context=context.Context()).end()
    tracer.start_span("carried", context=inside).end()
    assert link_of(exporter, "detached") == {}
    assert link_of(exporter, "carried") == {"tessary.prompt.name": "a", "tessary.prompt.version": "3"}


def test_processor_works_on_a_real_tracer_provider():
    # Bug: the duck-typed processor lacks a method one SDK version calls, and the user's tracer fails at start,
    # at end, at flush or at shutdown.
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(PromptLinkSpanProcessor())
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    with prompt().linked():
        with provider.get_tracer("test").start_as_current_span("call"):
            pass
    assert provider.force_flush() is True
    provider.shutdown()
    assert len(exporter.get_finished_spans()) == 1


def test_link_without_opentelemetry_installed_is_a_no_op(monkeypatch):
    # Bug: link() or linked() raises ImportError in a process with no tracing.
    for module in [m for m in sys.modules if m == "opentelemetry" or m.startswith(("opentelemetry.", "tessary_prompts.otel"))]:
        monkeypatch.delitem(sys.modules, module)
    monkeypatch.setitem(sys.modules, "opentelemetry", None)
    p = prompt()
    assert p.link() is None
    with p.linked():
        pass
