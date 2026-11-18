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
    { host: "http://x", apiKey: "k", timeoutMs: 0 },
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

test("update body is exact and evicts the name", async () => {
  // Bug: camelCase lost on newLabels, or a cached production read survives a promote.
  server.script({ status: 200, body: envelope({ name: "a" }) }, { status: 200, body: envelope({ name: "a", version: 2 }) });
  const client = make();
  await client.get("a");
  assert.equal((await client.update({ name: "a", version: 2, newLabels: ["production"] })).version, 2);
  const patch = server.requests[1];
  assert.deepEqual([patch.method, patch.path], ["PATCH", "/v1/prompts/labels"]);
  assert.deepEqual(patch.body, { name: "a", version: 2, newLabels: ["production"] });
  assert.equal(client.cache.size(), 0);
});

test("update sends removeLabels", async () => {
  // Bug: removeLabels never reaches the server, so a label cannot be taken off from code.
  server.script({ status: 200, body: envelope({ name: "a", version: 2 }) });
  await make().update({ name: "a", version: 2, removeLabels: ["staging"] });
  assert.deepEqual(server.requests[0].body, { name: "a", version: 2, removeLabels: ["staging"] });
});

test("bad update arguments throw", async () => {
  // Bug: a label move with no prompt or no version reaches the server.
  const client = make();
  await rejects(client.update({ name: "", version: 1, newLabels: ["x"] }), TypeError);
  await rejects(client.update({ name: "a", version: 0, newLabels: ["x"] }), TypeError);
  assert.equal(server.requests.length, 0);
});

test("update 404 names the version", async () => {
  // Bug: a missing version is reported without saying which one.
  server.script({ status: 404, body: apiError(404, "PROMPTS.VERSION_NOT_FOUND", "Prompt a has no version 9") });
  const error = await rejects(make().update({ name: "a", version: 9, newLabels: ["production"] }), PromptNotFoundError);
  assert.deepEqual([error.promptName, error.version], ["a", 9]);
});

const DELETED = { meta: { status: 200, success: true }, data: { deleted: true } };

test("delete by label evicts the name", async () => {
  // Bug: a stale cached version is served after a delete.
  server.script({ status: 200, body: envelope({ name: "a" }) }, { status: 200, body: DELETED }, { status: 200, body: envelope({ name: "a", version: 2 }) });
  const client = make();
  await client.get("a");
  assert.equal(await client.delete("a", { label: "production" }), true);
  const req = server.requests[1];
  assert.deepEqual([req.method, req.path, req.query, req.body], ["DELETE", "/v1/prompts", "name=a&label=production", null]);
  assert.equal((await client.get("a")).version, 2);
  assert.equal(server.requests.length, 3);
});

test("delete sends the version or nothing", async () => {
  // Bug: the version is dropped, so a version delete removes the whole prompt.
  server.script({ status: 200, body: DELETED }, { status: 200, body: { meta: {}, data: {} } });
  const client = make();
  assert.equal(await client.delete("a", { version: 3 }), true);
  assert.equal(await client.delete("a"), false);
  assert.deepEqual(
    server.requests.map((r) => r.query),
    ["name=a&version=3", "name=a"],
  );
});

test("bad delete arguments throw before any request", async () => {
  // Bug: a delete with both a version and a label, or no name, reaches the server.
  const client = make();
  await rejects(client.delete(""), TypeError);
  await rejects(client.delete("a", { version: 0 }), TypeError);
  await rejects(client.delete("a", { version: 1, label: "x" }), TypeError);
  assert.equal(server.requests.length, 0);
});

test("delete is not retried on 503", async () => {
  // Bug: a delete that committed is sent again and deletes the next version a label moved to.
  server.script({ status: 503 }, { status: 200, body: DELETED });
  await rejects(make().delete("a", { label: "production" }), PromptApiError);
  assert.equal(server.requests.length, 1);
});

test("list passes filters and returns the page", async () => {
  // Bug: filters are dropped from the query, or the page comes back without its meta.
  const page = {
    data: [{ name: "a", type: "text", versions: [1], labels: [], tags: [], lastUpdatedAt: "x", lastConfig: {} }],
    meta: { page: 2, limit: 5, totalItems: 6, totalPages: 2 },
  };
  server.script({ status: 200, body: { meta: {}, data: page } }, { status: 200, body: { meta: {}, data: page } });
  const client = make();
  const got = await client.list({
    name: "a",
    label: "production",
    tag: "t",
    page: 2,
    limit: 5,
    fromUpdatedAt: "2026-11-01T00:00:00Z",
    toUpdatedAt: "2026-11-02T00:00:00Z",
  });
  assert.deepEqual(got, page);
  await client.list();
  const [first, second] = server.requests;
  assert.deepEqual([first.method, first.path], ["GET", "/v1/prompts"]);
  assert.equal(
    first.query,
    "name=a&label=production&tag=t&page=2&limit=5&fromUpdatedAt=2026-11-01T00%3A00%3A00Z&toUpdatedAt=2026-11-02T00%3A00%3A00Z",
  );
  assert.equal(second.query, "");
});

test("clearCache forces a fetch", async () => {
  // Bug: there is no way to drop the cache, so a process serves a moved label for a full TTL.
  server.script({ status: 200, body: envelope({ version: 1 }) }, { status: 200, body: envelope({ version: 2 }) });
  const client = make();
  assert.equal((await client.get("a")).version, 1);
  client.clearCache();
  assert.equal(client.cache.size(), 0);
  assert.equal((await client.get("a")).version, 2);
  assert.equal(server.requests.length, 2);
});

test("per-call retries and timeout override the client", async () => {
  // Bug: a caller cannot shorten the 16 s worst case for one call.
  server.script({ status: 503 }, { status: 503 }, { status: 200, body: envelope(), delayMs: 500 });
  const client = make({ maxRetries: 4, timeoutMs: 5000 });
  await rejects(client.get("a", { maxRetries: 0 }), PromptApiError);
  assert.equal(server.requests.length, 1);
  await rejects(client.get("a", { maxRetries: 1, fetchTimeoutMs: 50 }), PromptTransportError);
  assert.equal(server.requests.length, 3);
});

test("retries are capped at four", async () => {
  // Bug: a large retry count parks the caller for minutes on a dead instance.
  server.script(...Array.from({ length: 12 }, () => ({ status: 503 })));
  const client = make({ maxRetries: 10 });
  await rejects(client.get("a"), PromptApiError);
  assert.equal(server.requests.length, 5);
  await rejects(client.get("a", { maxRetries: 9 }), PromptApiError);
  assert.equal(server.requests.length, 10);
  await rejects(client.get("a", { maxRetries: -3 }), PromptApiError);
  assert.equal(server.requests.length, 11);
});

test("a bad fetch timeout throws before any request", async () => {
  // Bug: a zero or negative timeout reaches AbortSignal.timeout, which then fails every call at once.
  await rejects(make().get("a", { fetchTimeoutMs: 0 }), TypeError);
  assert.equal(server.requests.length, 0);
});

test("a malformed success body returns the fallback", async () => {
  // Bug: a 2xx without version (or with a wrong-typed field) is returned as a prompt with undefined fields.
  for (const overrides of [
    { version: undefined },
    { version: "3" },
    { version: 1.5 },
    { name: 7 },
    { type: "json" },
    { prompt: null },
    { type: "chat", prompt: "not a list" },
    { type: "text", prompt: [{ role: "user", content: "x" }] },
  ]) {
    server.script({ status: 200, body: envelope(overrides) }, { status: 200, body: envelope(overrides) });
    const client = make();
    assert.equal((await client.get("a", { fallback: "x" })).isFallback, true, JSON.stringify(overrides));
    await rejects(client.get("a"), PromptTransportError);
  }
});

test("a write with a malformed body still evicts the name", async () => {
  // Bug: the write committed but the bad body threw first, so the old cached version is served on.
  server.script({ status: 200, body: envelope({ name: "a" }) }, { status: 201, body: envelope({ name: "a", version: undefined }) });
  const client = make();
  await client.get("a");
  await rejects(client.create({ name: "a", prompt: "p" }), PromptTransportError);
  assert.equal(client.cache.size(), 0);
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
