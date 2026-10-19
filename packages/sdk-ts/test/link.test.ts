// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { test } from "node:test";

import { TextPrompt, type SpanLike } from "../src/index.ts";

class FakeSpan implements SpanLike {
  readonly attributes: Record<string, unknown> = {};
  setAttribute(key: string, value: string): this {
    this.attributes[key] = value;
    return this;
  }
}

test("link sets both attributes as strings", () => {
  // Bug: the version is sent as a number, which the ingest contract does not read.
  const span = new FakeSpan();
  const prompt = new TextPrompt({ name: "a", version: 3, prompt: "p" });
  assert.deepEqual(prompt.spanAttributes(), { "tessary.prompt.name": "a", "tessary.prompt.version": "3" });
  assert.equal(prompt.link(span), span);
  assert.deepEqual(span.attributes, { "tessary.prompt.name": "a", "tessary.prompt.version": "3" });
});

test("fallback sets nothing", () => {
  // Bug: fallback traffic is counted against a version that never served it.
  const span = new FakeSpan();
  new TextPrompt({ name: "a", version: 0, prompt: "p", isFallback: true }).link(span);
  assert.deepEqual(span.attributes, {});
});

test("span without setAttribute is returned unchanged", () => {
  // Bug: a foreign or no-op span object crashes the caller.
  const span = {} as SpanLike;
  assert.equal(new TextPrompt({ name: "a", version: 3, prompt: "p" }).link(span), span);
});
