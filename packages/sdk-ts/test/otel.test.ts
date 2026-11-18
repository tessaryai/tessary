// SPDX-License-Identifier: Apache-2.0
// Automatic linking through OpenTelemetry context, on real tracer providers: the newest SDK and the
// last release of the 1.x line, whose processor interface has no onEnding.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { after, before, test } from "node:test";

import { ROOT_CONTEXT, context, type Tracer } from "@opentelemetry/api";
import { AsyncLocalStorageContextManager } from "@opentelemetry/context-async-hooks";
import { InMemorySpanExporter, SimpleSpanProcessor, TracerProvider } from "@opentelemetry/sdk-trace";
import * as oldest from "@opentelemetry/sdk-trace-base-oldest";

import { ChatPrompt, TextPrompt } from "../src/index.ts";
import { PromptLinkSpanProcessor, linkActiveSpan, withPrompt } from "../src/otel.ts";

const manager = new AsyncLocalStorageContextManager();
before(() => {
  context.setGlobalContextManager(manager.enable());
});
after(() => {
  manager.disable();
  context.disable();
});

function setup(): { tracer: Tracer; link: (name: string) => Record<string, unknown>; provider: TracerProvider } {
  const exporter = new InMemorySpanExporter();
  const provider = new TracerProvider({ spanProcessors: [new PromptLinkSpanProcessor(), new SimpleSpanProcessor({ exporter })] });
  const link = (name: string): Record<string, unknown> => {
    const spans = exporter.getFinishedSpans().filter((s) => s.name === name);
    assert.equal(spans.length, 1, `one span named ${name}`);
    return Object.fromEntries(Object.entries(spans[0].attributes).filter(([key]) => key.startsWith("tessary.prompt.")));
  };
  return { tracer: provider.getTracer("test"), link, provider };
}

function prompt(name = "a", version = 3, isFallback = false): TextPrompt {
  return new TextPrompt({ name, version, prompt: "p", isFallback });
}

const A3 = { "tessary.prompt.name": "a", "tessary.prompt.version": "3" };

test("span started inside the block carries the link", async () => {
  // Bug: the review's main adoption gap; a model-call span has no link unless the user calls link(span) by hand.
  const { tracer, link } = setup();
  await withPrompt(prompt(), async () => {
    await tracer.startActiveSpan("agent", async (agent) => {
      await Promise.resolve();
      tracer.startSpan("llm").end();
      agent.end();
    });
  });
  assert.deepEqual(link("llm"), A3);
  assert.deepEqual(link("agent"), A3);
});

test("span started after the block carries no link", () => {
  // Bug: the context leaks and later calls are counted under the wrong prompt.
  const { tracer, link } = setup();
  assert.equal(
    withPrompt(prompt(), () => 7),
    7,
  );
  tracer.startSpan("llm").end();
  assert.deepEqual(link("llm"), {});
});

test("nested blocks link the inner prompt", () => {
  // Bug: the outer prompt wins, or leaving the inner block drops the outer link.
  const { tracer, link } = setup();
  withPrompt(prompt("outer", 1), () => {
    withPrompt(new ChatPrompt({ name: "inner", version: 2, prompt: [] }), () => tracer.startSpan("inner-call").end());
    tracer.startSpan("outer-call").end();
  });
  assert.deepEqual(link("inner-call"), { "tessary.prompt.name": "inner", "tessary.prompt.version": "2" });
  assert.deepEqual(link("outer-call"), { "tessary.prompt.name": "outer", "tessary.prompt.version": "1" });
});

test("fallback prompt links nothing", () => {
  // Bug: usage is counted for version 0, or a fallback inside a linked block is counted under the outer prompt.
  const { tracer, link } = setup();
  withPrompt(prompt("a", 0, true), () => tracer.startSpan("alone").end());
  withPrompt(prompt("outer", 1), () =>
    withPrompt(prompt("inner", 0, true), () => {
      tracer.startSpan("nested").end();
      tracer.startActiveSpan("active", (span) => {
        assert.equal(linkActiveSpan(prompt("inner", 0, true)), span);
        span.end();
      });
    }),
  );
  assert.deepEqual(link("alone"), {});
  assert.deepEqual(link("nested"), {});
  assert.deepEqual(link("active"), {});
});

test("linkActiveSpan links the active span and nothing else", () => {
  // Bug: linking needs the caller to find the current span by hand, links a parent, or throws with no span.
  const { tracer, link } = setup();
  assert.equal(linkActiveSpan(prompt()), undefined);
  tracer.startActiveSpan("outer", (outer) => {
    tracer.startActiveSpan("call", (span) => {
      assert.equal(linkActiveSpan(prompt()), span);
      span.end();
    });
    outer.end();
  });
  assert.deepEqual(link("call"), A3);
  assert.deepEqual(link("outer"), {});
});

test("an explicit parent context decides the link", () => {
  // Bug: the processor reads the ambient context instead of the context the span was started with.
  const { tracer, link } = setup();
  const inside = withPrompt(prompt(), () => {
    tracer.startSpan("detached", {}, ROOT_CONTEXT).end();
    return context.active();
  });
  tracer.startSpan("carried", {}, inside).end();
  assert.deepEqual(link("detached"), {});
  assert.deepEqual(link("carried"), A3);
});

test("processor works on a real tracer provider", async () => {
  // Bug: the duck-typed processor lacks a method one SDK version calls, and the user's tracer fails at start,
  // at end, at flush or at shutdown.
  const { tracer, link, provider } = setup();
  withPrompt(prompt(), () => tracer.startSpan("call").end());
  await provider.forceFlush();
  assert.deepEqual(link("call"), A3);
  await provider.shutdown();
});

test("processor works on the oldest supported tracer provider", async () => {
  // Bug: a processor method or signature that only the newest SDK accepts breaks users on an older SDK.
  const exporter = new oldest.InMemorySpanExporter();
  const provider = new oldest.BasicTracerProvider();
  provider.addSpanProcessor(new PromptLinkSpanProcessor());
  provider.addSpanProcessor(new oldest.SimpleSpanProcessor(exporter));
  withPrompt(prompt(), () => provider.getTracer("test").startSpan("call").end());
  await provider.forceFlush();
  const [span] = exporter.getFinishedSpans();
  assert.equal(span.attributes["tessary.prompt.name"], "a");
  assert.equal(span.attributes["tessary.prompt.version"], "3");
  await provider.shutdown();
});

test("importing the main entry does not import OpenTelemetry", () => {
  // Bug: a hard OpenTelemetry dependency sneaks into the main entry, so an app without it fails to load the package.
  const index = new URL("../src/index.ts", import.meta.url).href;
  const script = `
    import { registerHooks } from "node:module";
    registerHooks({
      resolve(specifier, ctx, next) {
        if (specifier.startsWith("@opentelemetry/") || specifier.endsWith("/otel.ts")) throw new Error("imported " + specifier);
        return next(specifier, ctx);
      },
    });
    await import(${JSON.stringify(index)});
  `;
  const run = spawnSync(process.execPath, ["--input-type=module", "-e", script], { encoding: "utf8" });
  assert.equal(run.status, 0, run.stderr);
});
