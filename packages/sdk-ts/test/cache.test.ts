// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { test } from "node:test";

import { PromptCache } from "../src/cache.ts";
import { PromptApiError, PromptNotFoundError } from "../src/errors.ts";
import { TessaryPrompts, TextPrompt } from "../src/index.ts";
import { Clock, StubServer, envelope } from "./stub-server.ts";

function text(version: number): TextPrompt {
  return new TextPrompt({ name: "a", version, prompt: `v${version}` });
}

function loader(...results: Array<TextPrompt | Error>) {
  const load = async (): Promise<TextPrompt> => {
    load.calls += 1;
    const next = results.shift();
    if (next instanceof Error) throw next;
    return next as TextPrompt;
  };
  load.calls = 0;
  return load;
}

const quiet = { debug: () => {} };

test("fresh hit skips the loader", async () => {
  // Bug: the TTL is ignored and every call goes to the network.
  const clock = new Clock();
  const cache = new PromptCache(clock.read, quiet);
  const load = loader(text(1));
  const first = await cache.get("a|label:production", 60, load);
  clock.now = 59_000;
  assert.equal(await cache.get("a|label:production", 60, load), first);
  assert.equal(load.calls, 1);
});

test("stale hit returns the old value and refreshes once", async () => {
  // Bug: the refresh blocks the caller, or every stale call starts its own refresh.
  const clock = new Clock();
  const cache = new PromptCache(clock.read, quiet);
  const load = loader(text(1), text(2));
  await cache.get("k", 60, load);
  clock.now = 61_000;
  assert.equal((await cache.get("k", 60, load)).version, 1);
  assert.equal(load.calls, 2);
  assert.equal((await cache.get("k", 60, load)).version, 1);
  assert.equal(load.calls, 2);
  await cache.whenIdle();
  assert.equal((await cache.get("k", 60, load)).version, 2);
});

test("refresh failure keeps the stale entry", async () => {
  // Bug: a flaky backend empties the cache.
  const clock = new Clock();
  const logged: Array<Record<string, unknown>> = [];
  const cache = new PromptCache(clock.read, { debug: (_message, fields) => logged.push(fields) });
  const load = loader(text(1), new PromptApiError(503, "http_503", "down"));
  await cache.get("k", 60, load);
  clock.now = 61_000;
  await cache.get("k", 60, load);
  await cache.whenIdle();
  assert.equal(cache.size(), 1);
  assert.equal((await cache.get("k", 60, load)).version, 1);
  assert.deepEqual(logged, [{ key: "k", error: "PromptApiError" }]);
});

test("refresh 404 evicts", async () => {
  // Bug: a deleted prompt is served forever.
  const clock = new Clock();
  const cache = new PromptCache(clock.read, quiet);
  const load = loader(text(1), new PromptNotFoundError("gone", "a", null, null), text(3));
  await cache.get("k", 60, load);
  clock.now = 61_000;
  await cache.get("k", 60, load);
  await cache.whenIdle();
  assert.equal(cache.size(), 0);
  assert.equal((await cache.get("k", 60, load)).version, 3);
  assert.equal(load.calls, 3);
});

test("ttl 0 bypasses the cache", async () => {
  // Bug: cacheTtlSeconds 0 still caches.
  const cache = new PromptCache(new Clock().read, quiet);
  const load = loader(text(1), text(2));
  await cache.get("k", 0, load);
  await cache.get("k", 0, load);
  assert.equal(load.calls, 2);
  assert.equal(cache.size(), 0);
});

test("per-call ttl wins", async () => {
  // Bug: the per-call override is ignored in favour of the client TTL.
  const server = await StubServer.start();
  try {
    const clock = new Clock();
    server.script({ status: 200, body: envelope() }, { status: 200, body: envelope({ version: 4 }) });
    const client = new TessaryPrompts({ host: server.url, apiKey: "tsy_q_test", cacheTtlSeconds: 60, clock: clock.read });
    await client.get("team/a/b", { cacheTtlSeconds: 1 });
    clock.now = 2_000;
    await client.get("team/a/b", { cacheTtlSeconds: 1 });
    await client.cache.whenIdle();
    assert.equal(server.requests.length, 2);
  } finally {
    await server.close();
  }
});

test("key derivation keeps version and label apart", () => {
  // Bug: a version read and a label read collide in one cache slot.
  assert.equal(PromptCache.key("a", 3, undefined), "a|version:3");
  assert.equal(PromptCache.key("a", undefined, "staging"), "a|label:staging");
  assert.equal(PromptCache.key("a", undefined, undefined), "a|label:production");
});

test("evictName is exact", async () => {
  // Bug: evicting team/a also evicts team/ab.
  const cache = new PromptCache(new Clock().read, quiet);
  await cache.get("team/a|label:production", 60, loader(text(1)));
  await cache.get("team/a|version:1", 60, loader(text(1)));
  await cache.get("team/ab|label:production", 60, loader(text(1)));
  cache.evictName("team/a");
  assert.equal(cache.size(), 1);
  await cache.get("team/ab|label:production", 60, loader());
});

test("concurrent cold misses share one request", async () => {
  // Bug: two first calls on an empty cache make two requests.
  const cache = new PromptCache(new Clock().read, quiet);
  const load = loader(text(1));
  const [a, b] = await Promise.all([cache.get("k", 60, load), cache.get("k", 60, load)]);
  assert.equal(a, b);
  assert.equal(load.calls, 1);
});

test("a failed cold miss is not cached and rejects every waiter", async () => {
  // Bug: a shared failed load is cached, or leaves a waiter hanging.
  const cache = new PromptCache(new Clock().read, quiet);
  const load = loader(new PromptApiError(503, "http_503", "down"), text(2));
  const results = await Promise.allSettled([cache.get("k", 60, load), cache.get("k", 60, load)]);
  assert.deepEqual(results.map((r) => r.status), ["rejected", "rejected"]);
  assert.equal(load.calls, 1);
  assert.equal((await cache.get("k", 60, load)).version, 2);
});

/** A loader that answers only when the test releases it. */
function held() {
  let release: (value: TextPrompt) => void = () => {};
  const load = (): Promise<TextPrompt> =>
    new Promise<TextPrompt>((resolve) => {
      release = resolve;
    });
  return { load, release: (value: TextPrompt) => release(value) };
}

const tick = () => new Promise<void>((resolve) => setImmediate(resolve));

test("a refresh in flight during a write does not store the old value", { timeout: 5_000 }, async () => {
  // Bug: a background refresh that read v1 before create() promoted v2 stores v1 after evictName ran,
  // so the process that just made the write serves the old version for a whole TTL.
  const clock = new Clock();
  const cache = new PromptCache(clock.read, quiet);
  await cache.get("a|label:production", 60, loader(text(1)));
  clock.now = 61_000;
  const refresh = held();
  assert.equal((await cache.get("a|label:production", 60, refresh.load)).version, 1);
  await tick();
  cache.evictName("a");
  refresh.release(text(1));
  await tick();
  assert.equal(cache.size(), 0);
  assert.equal((await cache.get("a|label:production", 60, loader(text(2)))).version, 2);
});

test("a cold load in flight during a write is neither stored nor joined", { timeout: 5_000 }, async () => {
  // Bug: a cold load that started before the write stores its old value afterwards, and callers
  // that arrive after the write join the pre-write request.
  const cache = new PromptCache(new Clock().read, quiet);
  const cold = held();
  const before = cache.get("a|label:production", 60, cold.load);
  await tick();
  cache.evictName("a");
  const after = loader(text(2));
  assert.equal((await cache.get("a|label:production", 60, after)).version, 2);
  assert.equal(after.calls, 1);
  cold.release(text(1));
  assert.equal((await before).version, 1);
  await tick();
  assert.equal((await cache.get("a|label:production", 60, loader())).version, 2);
});

test("evict also stops a load in flight from storing", { timeout: 5_000 }, async () => {
  // Bug: a 404 seen by an uncached read evicts the key, and a cached load of the same key that was
  // already in flight stores the old prompt again.
  const cache = new PromptCache(new Clock().read, quiet);
  const cold = held();
  const pending = cache.get("a|label:production", 60, cold.load);
  await tick();
  cache.evict("a|label:production");
  cache.evict("a|label:production");
  cold.release(text(3));
  assert.equal((await pending).version, 3);
  assert.equal(cache.size(), 0);
});
