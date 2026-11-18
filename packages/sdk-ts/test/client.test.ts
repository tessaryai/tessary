// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";

import {
  PromptApiError,
  PromptNotFoundError,
  PromptTransportError,
  TessaryPrompts,
  TextPrompt,
  type TessaryPromptsOptions,
} from "../src/index.ts";
import { VERSION } from "../src/http.ts";
import { Clock, Sleeps, StubServer, apiError, envelope } from "./stub-server.ts";

let server: StubServer;
beforeEach(async () => {
  server = await StubServer.start();
});
afterEach(async () => {
  await server.close();
});

function make(options: Partial<TessaryPromptsOptions> = {}): TessaryPrompts {
  return new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test", sleep: new Sleeps().sleep, ...options });
}

async function rejects<T extends Error>(promise: Promise<unknown>, type: new (...args: never[]) => T): Promise<T> {
  try {
    await promise;
  } catch (error) {
    assert.ok(error instanceof type, `expected ${type.name}, got ${String(error)}`);
    return error;
  }
  assert.fail(`expected ${type.name}`);
}

test("successful fetch sends the right request and parses the envelope", async () => {
  // Bug: wrong path, wrong headers, a raw "/" in the query, or fields read from the wrong place.
  server.script({ status: 200, body: envelope() });
  const prompt = await make().get("team/a/b");
  const [req] = server.requests;
  assert.equal(req.method, "GET");
  assert.equal(req.path, "/v1/prompts/fetch");
  assert.equal(req.query, "name=team%2Fa%2Fb");
  assert.equal(req.headers.authorization, "Bearer tsy_q_test");
  assert.equal(req.headers.accept, "application/json");
  assert.equal(req.headers["user-agent"], `tessary-prompts-ts/${VERSION}`);
  assert.equal(req.headers["content-type"], undefined);
  assert.ok(prompt instanceof TextPrompt);
  assert.deepEqual([prompt.name, prompt.version, prompt.type], ["team/a/b", 3, "text"]);
  assert.deepEqual(prompt.labels, ["production", "latest"]);
  assert.deepEqual(prompt.tags, ["onboarding"]);
  assert.deepEqual(prompt.config, { temperature: 0.2 });
  assert.equal(prompt.commitMessage, "warmer tone");
  assert.equal(prompt.isFallback, false);
});

test("version and label are sent as query parameters", async () => {
  // Bug: version or label dropped, or sent in the wrong form.
  server.script({ status: 200, body: envelope({ version: 2 }) }, { status: 200, body: envelope() });
  const client = make();
  await client.get("a", { version: 2 });
  await client.get("a", { label: "staging" });
  assert.deepEqual(
    server.requests.map((r) => r.query),
    ["name=a&version=2", "name=a&label=staging"],
  );
});

test("bad get arguments throw before any request", async () => {
  // Bug: an invalid read reaches the server, or both version and label are sent.
  const client = make();
  for (const options of [
    { version: 2, label: "staging" },
    { version: 0 },
    { version: 1.5 },
    { cacheTtlSeconds: -1 },
    { fallback: 3 as unknown as string },
  ]) {
    await rejects(client.get("a", options), TypeError);
  }
  await rejects(client.get(""), TypeError);
  assert.equal(server.requests.length, 0);
});

test("bad client arguments throw", () => {
  // Bug: a client that can never work is built and fails later, far from the cause.
  for (const options of [
    { host: "ftp://x", apiKey: "k" },
    { host: "http://x", apiKey: "" },
    { host: "http://x", apiKey: "k", cacheTtlSeconds: -1 },
    { host: "http://x", apiKey: "k", maxRetries: -1 },
  ]) {
    assert.throws(() => new TessaryPrompts(options), TypeError);
  }
});

test("404 raises and evicts", async () => {
  // Bug: a deleted prompt keeps being served from the cache.
  server.script(
    { status: 200, body: envelope() },
    { status: 404, body: apiError(404, "PROMPTS.NOT_FOUND", "Prompt team/a/b not found") },
  );
  const client = make();
  await client.get("team/a/b");
  assert.equal(client.cache.size(), 1);
  const error = await rejects(client.get("team/a/b", { cacheTtlSeconds: 0 }), PromptNotFoundError);
  assert.equal(error.promptName, "team/a/b");
  assert.equal(error.version, null);
  assert.equal(error.label, null);
  assert.equal(error.message, "Prompt team/a/b not found");
  assert.equal(client.cache.size(), 0);
});

test("404 with fallback returns the fallback", async () => {
  // Bug: the fallback is ignored when the prompt does not exist.
  server.script({ status: 404, body: apiError(404, "PROMPTS.LABEL_NOT_FOUND", "no label") });
  const client = make();
  const prompt = await client.get("a", { label: "staging", fallback: "x" });
  assert.equal(prompt.isFallback, true);
  assert.equal(client.cache.size(), 0);
});

test("a chat fallback holds a placeholder that compile fills", async () => {
  // Bug: fallback was typed string | ChatMessage[], so a fallback shaped like the stored chat prompt, placeholder
  // included, did not type-check, though fallbackPrompt and the Python SDK both take one.
  server.script({ status: 404, body: apiError(404, "PROMPTS.NOT_FOUND", "no prompt") });
  const prompt = await make().get("a", {
    fallback: [{ role: "system", content: "Be {{tone}}." }, { type: "placeholder", name: "history" }],
  });
  assert.equal(prompt.type, "chat");
  assert.deepEqual(prompt.compile({ tone: "brief", history: [{ role: "user", content: "hi" }] }), [
    { role: "system", content: "Be brief." },
    { role: "user", content: "hi" },
  ]);
});

test("429 then 200 honours Retry-After", async () => {
  // Bug: Retry-After ignored.
  const sleeps = new Sleeps();
  server.script(
    { status: 429, headers: { "retry-after": "1" }, body: { ok: false, error: { code: "rate.limited", message: "slow" } } },
    { status: 200, body: envelope() },
  );
  assert.equal((await make({ sleep: sleeps.sleep }).get("a")).version, 3);
  assert.deepEqual(sleeps.calls, [1000]);
  assert.equal(server.requests.length, 2);
});

test("Retry-After is capped at 30 seconds", async () => {
  // Bug: a hostile or broken Retry-After parks the caller for minutes.
  const sleeps = new Sleeps();
  server.script({ status: 429, headers: { "retry-after": "600" } }, { status: 200, body: envelope() });
  await make({ sleep: sleeps.sleep }).get("a");
  assert.deepEqual(sleeps.calls, [30_000]);
});

test("429 without header waits backoff plus jitter", async () => {
  // Bug: no jitter, so every client retries in lockstep.
  const sleeps = new Sleeps();
  server.script({ status: 429 }, { status: 200, body: envelope() });
  await make({ sleep: sleeps.sleep, random: () => 0.5 }).get("a");
  assert.deepEqual(sleeps.calls, [625]);
});

test("503 three times on a read gives up after the retries", async () => {
  // Bug: retries unbounded, or absent.
  const sleeps = new Sleeps();
  const logged: Array<[string, Record<string, unknown>]> = [];
  server.script(...Array.from({ length: 3 }, () => ({ status: 503, body: "<html>upstream down</html>" })));
  const client = make({ sleep: sleeps.sleep, logger: { debug: (message, fields) => logged.push([message, fields]) } });
  const error = await rejects(client.get("a"), PromptApiError);
  assert.equal(error.status, 503);
  assert.equal(error.code, "http_503");
  assert.equal(server.requests.length, 3);
  assert.equal(sleeps.calls.length, 2);
  assert.deepEqual(logged[0], ["retrying", { method: "GET", path: "/v1/prompts/fetch", cause: 503, attempt: 1, attempts: 3 }]);
  assert.ok(!JSON.stringify(logged).includes("tsy_q_test"));
});

test("timeout is a transport error and the fallback covers it", async () => {
  // Bug: a timeout escapes as a raw AbortError, so the fallback never applies.
  server.script({ status: 200, body: envelope(), delayMs: 500 }, { status: 200, body: envelope(), delayMs: 500 });
  const client = make({ timeoutMs: 50, maxRetries: 0 });
  await rejects(client.get("a"), PromptTransportError);
  assert.equal((await client.get("a", { fallback: "x" })).isFallback, true);
});

test("timeout on a read is retried", async () => {
  // Bug: a slow first answer fails the read although a retry would succeed.
  const sleeps = new Sleeps();
  server.script({ status: 200, body: envelope(), delayMs: 500 }, { status: 200, body: envelope() });
  assert.equal((await make({ timeoutMs: 200, sleep: sleeps.sleep }).get("a")).version, 3);
  assert.equal(sleeps.calls.length, 1);
});

test("401 filter shape is parsed", async () => {
  // Bug: the filter's {"ok":false,"error":{...}} body crashes the envelope parser.
  server.script({ status: 401, body: { ok: false, error: { code: "auth.unauthorized", message: "bad key" } } });
  const error = await rejects(make().get("a"), PromptApiError);
  assert.deepEqual([error.status, error.code, error.message], [401, "auth.unauthorized", "401 auth.unauthorized: bad key"]);
});

test("403 on a write carries the envelope code", async () => {
  // Bug: a scope error is reported as the wrong class or loses its code.
  server.script({ status: 403, body: apiError(403, "PROMPTS.WRONG_KEY_SCOPE", "this API key cannot write prompts") });
  const error = await rejects(make().create({ name: "a", prompt: "p" }), PromptApiError);
  assert.deepEqual([error.status, error.code], [403, "PROMPTS.WRONG_KEY_SCOPE"]);
  assert.equal(server.requests.length, 1);
});

test("POST is not retried on 503", async () => {
  // Bug: a retried insert that had committed creates a duplicate version.
  server.script({ status: 503 }, { status: 200, body: envelope() });
  const error = await rejects(make().create({ name: "a", prompt: "p" }), PromptApiError);
  assert.equal(error.status, 503);
  assert.equal(server.requests.length, 1);
});

test("POST is not retried on a timeout", async () => {
  // Bug: a write that timed out after committing is sent again.
  server.script({ status: 200, body: envelope(), delayMs: 500 });
  await rejects(make({ timeoutMs: 50 }).create({ name: "a", prompt: "p" }), PromptTransportError);
  assert.equal(server.requests.length, 1);
});

test("POST is retried on 429 and accepts 200 or 201", async () => {
  // Bug: a create fails under the write rate limit, or a 201 is read as an error.
  server.script(
    { status: 429, headers: { "retry-after": "0" } },
    { status: 200, body: envelope({ version: 1 }) },
    { status: 201, body: envelope({ version: 2 }) },
  );
  const client = make();
  assert.equal((await client.create({ name: "a", prompt: "p" })).version, 1);
  assert.equal((await client.create({ name: "a", prompt: "p" })).version, 2);
  assert.equal(server.requests.length, 3);
});

test("302 is a transport error after one request", async () => {
  // Bug: a redirect is followed, re-sending the bearer key to another origin.
  server.script({ status: 302, headers: { location: `${server.url}/elsewhere` } });
  await rejects(make().get("a"), PromptTransportError);
  assert.equal(server.requests.length, 1);
});

test("create body is exact and evicts the name", async () => {
  // Bug: wrong field names, absent optionals sent as null, or a stale read survives a write.
  const body = [{ role: "user", content: "{{ q }}" }];
  server.script({ status: 200, body: envelope({ name: "a" }) }, { status: 200, body: envelope({ name: "a", type: "chat", prompt: body }) });
  const client = make();
  await client.get("a");
  await client.create({ name: "a", prompt: body, type: "chat", labels: ["staging"] });
  const create = server.requests[1];
  assert.deepEqual([create.method, create.path], ["POST", "/v1/prompts"]);
  assert.equal(create.headers["content-type"], "application/json");
  assert.deepEqual(create.body, { name: "a", type: "chat", prompt: body, labels: ["staging"] });
  assert.equal(client.cache.size(), 0);
});

test("create sends every optional when given", async () => {
  // Bug: config, tags or the commit message never reach the server.
  server.script({ status: 200, body: envelope() });
  await make().create({ name: "a", prompt: "p", config: { t: 1 }, labels: ["x"], tags: ["y"], commitMessage: "m" });
  assert.deepEqual(server.requests[0].body, {
    name: "a",
    type: "text",
    prompt: "p",
    config: { t: 1 },
    labels: ["x"],
    tags: ["y"],
    commitMessage: "m",
  });
});

test("bad create arguments throw before any request", async () => {
  // Bug: a prompt whose body does not match its type reaches the server.
  const client = make();
  for (const input of [
    { name: "", prompt: "p" },
    { name: "a", prompt: "p", type: "json" as "text" },
    { name: "a", prompt: [{ role: "user", content: "m" }] },
    { name: "a", prompt: "p", type: "chat" as const },
  ]) {
    await rejects(client.create(input), TypeError);
  }
  assert.equal(server.requests.length, 0);
});

test("updateLabels body is exact and evicts the name", async () => {
  // Bug: camelCase lost on newLabels, or a cached production read survives a promote.
  server.script({ status: 200, body: envelope({ name: "a" }) }, { status: 200, body: envelope({ name: "a", version: 2 }) });
  const client = make();
  await client.get("a");
  assert.equal((await client.updateLabels("a", 2, ["production"])).version, 2);
  const patch = server.requests[1];
  assert.deepEqual([patch.method, patch.path], ["PATCH", "/v1/prompts/labels"]);
  assert.deepEqual(patch.body, { name: "a", version: 2, newLabels: ["production"] });
  assert.equal(client.cache.size(), 0);
});

test("bad updateLabels arguments throw", async () => {
  // Bug: a label move with no prompt or no version reaches the server.
  const client = make();
  await rejects(client.updateLabels("", 1, ["x"]), TypeError);
  await rejects(client.updateLabels("a", 0, ["x"]), TypeError);
  assert.equal(server.requests.length, 0);
});

test("updateLabels 404 names the version", async () => {
  // Bug: a missing version is reported without saying which one.
  server.script({ status: 404, body: apiError(404, "PROMPTS.VERSION_NOT_FOUND", "Prompt a has no version 9") });
  const error = await rejects(make().updateLabels("a", 9, ["production"]), PromptNotFoundError);
  assert.deepEqual([error.promptName, error.version], ["a", 9]);
});

test("host is normalised", async () => {
  // Bug: a trailing slash produces //v1 in the path.
  server.script({ status: 200, body: envelope() });
  await new TessaryPrompts({ host: `${server.url}/`, apiKey: "tsy_q_test" }).get("a");
  assert.equal(server.requests[0].path, "/v1/prompts/fetch");
});

test("stale-while-revalidate end to end", async () => {
  // Bug: the background refresh never reaches the network.
  const clock = new Clock();
  server.script({ status: 200, body: envelope({ version: 1 }) }, { status: 200, body: envelope({ version: 2 }) });
  const client = make({ clock: clock.read });
  assert.equal((await client.get("a")).version, 1);
  clock.now = 61_000;
  assert.equal((await client.get("a")).version, 1);
  await client.cache.whenIdle();
  assert.equal(server.requests.length, 2);
  assert.equal((await client.get("a")).version, 2);
});

test("non-JSON 2xx is a transport error", async () => {
  // Bug: a proxy's HTML page crashes the caller with a bare SyntaxError.
  server.script({ status: 200, body: "<html>hi</html>" });
  await rejects(make().get("a"), PromptTransportError);
});

test("2xx without a data object is a transport error", async () => {
  // Bug: an envelope with no data is read as a prompt with every field undefined.
  server.script({ status: 200, body: { meta: { status: 200, success: true } } });
  await rejects(make().get("a"), PromptTransportError);
});

test("connection refused is a transport error", async () => {
  // Bug: an unreachable host escapes as a raw fetch TypeError.
  await rejects(new TessaryPrompts({ host: "http://127.0.0.1:9", apiKey: "tsy_q_test", maxRetries: 0 }).get("a"), PromptTransportError);
});

test("defaults retry with the built-in sleep", async () => {
  // Bug: the default sleep never resolves, so a retried read hangs.
  server.script({ status: 429, headers: { "retry-after": "0" } }, { status: 200, body: envelope() });
  assert.equal((await new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test" }).get("a")).version, 3);
});
