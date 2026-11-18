// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { test } from "node:test";

import { ChatPrompt, TessaryPrompts, TextPrompt, type ChatMessage, type ChatPlaceholder } from "../src/index.ts";
import { StubServer, envelope } from "./stub-server.ts";

const text = (prompt: string) => new TextPrompt({ name: "a", version: 1, prompt });
const chat = (prompt: Array<ChatMessage | ChatPlaceholder>) => new ChatPrompt({ name: "a", version: 1, prompt });

async function withServer(run: (server: StubServer) => Promise<void>): Promise<void> {
  const server = await StubServer.start();
  try {
    await run(server);
  } finally {
    await server.close();
  }
}

test("substitution allows inner whitespace", () => {
  // Bug: only the tight {{name}} spelling is matched.
  assert.equal(text("Hi {{ name }} and {{name}}").compile({ name: "Ada" }), "Hi Ada and Ada");
});

test("null renders as empty", () => {
  // Bug: null renders as the string "null".
  assert.equal(text("Hi {{ name }}!").compile({ name: null }), "Hi !");
});

test("missing or undefined variable is left literal", () => {
  // Bug: a variable the caller did not pass silently becomes empty.
  assert.equal(text("Hi {{ name }}").compile(), "Hi {{ name }}");
  assert.equal(text("Hi {{ name }}").compile({ name: undefined }), "Hi {{ name }}");
});

test("inherited object keys are not variables", () => {
  // Bug: {{ constructor }} renders Object's constructor source.
  assert.equal(text("{{ constructor }}").compile({}), "{{ constructor }}");
});

test("non-identifier inner text is left literal", () => {
  // Bug: JSON or prose inside braces is eaten as a variable.
  const prompt = text('{{"a":1}} {{a b}} {{}}');
  assert.equal(prompt.compile({ a: 1, b: 2 }), '{{"a":1}} {{a b}} {{}}');
  assert.deepEqual(prompt.variables(), []);
});

test("number becomes string", () => {
  // Bug: String() is not applied to a non-string value.
  assert.equal(text("{{ count }} items").compile({ count: 3 }), "3 items");
});

test("variables keep first-seen order without duplicates", () => {
  // Bug: duplicates reported, or names sorted.
  assert.deepEqual(text("{{b}} {{a}} {{ b }}").variables(), ["b", "a"]);
});

test("chat variables scan every content and skip placeholders", () => {
  // Bug: only the first message, or the placeholder name, is reported.
  const prompt = chat([
    { role: "system", content: "You help {{ team }}" },
    { type: "placeholder", name: "history" },
    { role: "user", content: "{{ question }} for {{ team }}" },
  ]);
  assert.deepEqual(prompt.variables(), ["team", "question"]);
});

test("chat message content is compiled and other keys kept", () => {
  // Bug: chat bodies are not compiled, or extra message keys are dropped.
  const prompt = chat([{ role: "user", content: "Hi {{ name }}", name: "u1" }]);
  assert.deepEqual(prompt.compile({ name: "Ada" }), [{ role: "user", content: "Hi Ada", name: "u1" }]);
});

test("placeholder is filled and its items compiled", () => {
  // Bug: placeholder items are pasted in raw.
  const prompt = chat([{ type: "placeholder", name: "history" }, { role: "user", content: "next" }]);
  const out = prompt.compile({ q: "x" }, { history: [{ role: "user", content: "{{ q }}" }] });
  assert.deepEqual(out, [
    { role: "user", content: "x" },
    { role: "user", content: "next" },
  ]);
});

test("unresolved placeholder is kept in place", () => {
  // Bug: a placeholder the caller did not fill silently disappears.
  const prompt = chat([
    { role: "system", content: "s" },
    { type: "placeholder", name: "history" },
    { role: "user", content: "u" },
  ]);
  const out = prompt.compile({ history: "not a list" });
  assert.deepEqual(out[1], { type: "placeholder", name: "history" });
  assert.equal(out.length, 3);
  assert.equal(chat([{ type: "placeholder", name: "history" }]).compile().length, 1);
});

test("non-object placeholder item throws TypeError", () => {
  // Bug: string items pass straight through into the provider request.
  const prompt = chat([{ type: "placeholder", name: "history" }]);
  assert.throws(() => prompt.compile({}, { history: ["hi"] as unknown as ChatMessage[] }), TypeError);
  assert.throws(() => prompt.compile({}, { history: [{ role: "user" }] as unknown as ChatMessage[] }), TypeError);
  assert.throws(() => prompt.compile({}, { history: [null] as unknown as ChatMessage[] }), TypeError);
});

test("a list fills the placeholder and renders in text", () => {
  // Bug: a list-valued variable fills only one of the two places it is named.
  const notes = [{ role: "user", content: "n" }];
  const prompt = chat([
    { role: "system", content: "Notes: {{ notes }}" },
    { type: "placeholder", name: "notes" },
  ]);
  assert.deepEqual(prompt.compile({ notes }), [
    { role: "system", content: `Notes: ${String(notes)}` },
    { role: "user", content: "n" },
  ]);
});

test("fallback prompt is marked unversioned and still compiles", async () => {
  // Bug: a fallback is linked to a version, cached, or cannot be compiled.
  await withServer(async (server) => {
    server.script({ status: 503, body: "down" });
    const client = new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test", maxRetries: 0 });
    const prompt = await client.get("a", { fallback: "Hi {{ name }}" });
    assert.ok(prompt instanceof TextPrompt);
    assert.equal(prompt.isFallback, true);
    assert.equal(prompt.version, 0);
    assert.deepEqual([prompt.labels, prompt.tags, prompt.config], [[], [], {}]);
    assert.deepEqual(prompt.spanAttributes(), {});
    assert.equal(prompt.compile({ name: "Ada" }), "Hi Ada");
    assert.equal(client.cache.size(), 0);
  });
});

test("chat fallback is a chat prompt", async () => {
  // Bug: an array fallback is built as a text prompt.
  await withServer(async (server) => {
    server.script({ status: 503, body: "down" });
    const client = new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test", maxRetries: 0 });
    const prompt = await client.get("a", { fallback: [{ role: "user", content: "{{ q }}" }] });
    assert.ok(prompt instanceof ChatPrompt);
    assert.deepEqual(prompt.compile({ q: "x" }), [{ role: "user", content: "x" }]);
  });
});

test("fetched chat prompt is a chat prompt", async () => {
  // Bug: the wire type is ignored and every prompt is built as text.
  await withServer(async (server) => {
    const body = [
      { role: "system", content: "s" },
      { type: "placeholder", name: "h" },
    ];
    server.script({ status: 200, body: envelope({ type: "chat", prompt: body, config: null, labels: null, tags: null, commitMessage: null }) });
    const prompt = await new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test" }).get("team/a/b");
    assert.ok(prompt instanceof ChatPrompt);
    assert.equal(prompt.type, "chat");
    assert.deepEqual(prompt.prompt, body);
    assert.deepEqual([prompt.config, prompt.labels, prompt.tags, prompt.commitMessage], [{}, [], [], null]);
  });
});

test("toJSON uses the public prompt field names", () => {
  // Bug: a different field set leaks into a shape that mirrors the public API.
  const prompt = new TextPrompt({
    name: "a",
    version: 2,
    prompt: "p",
    config: { t: 1 },
    labels: ["x"],
    tags: ["y"],
    commitMessage: "m",
  });
  assert.deepEqual(JSON.parse(JSON.stringify(prompt)), {
    name: "a",
    version: 2,
    type: "text",
    prompt: "p",
    config: { t: 1 },
    labels: ["x"],
    tags: ["y"],
    commitMessage: "m",
    isFallback: false,
  });
});
