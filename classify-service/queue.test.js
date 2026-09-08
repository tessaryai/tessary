// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Unit tests for the ConcurrencyGate (node:test, no deps). Run by scripts/check-classify-service.sh.
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { ConcurrencyGate, GateRejection } = require('./queue');

const tick = () => new Promise((r) => setImmediate(r));

test('acquire resolves immediately up to maxInflight', async () => {
  const gate = new ConcurrencyGate({ maxInflight: 2, maxQueue: 4, timeoutMs: 1000 });
  const r1 = await gate.acquire();
  const r2 = await gate.acquire();
  assert.equal(gate.stats().inflight, 2);
  assert.equal(gate.stats().queued, 0);
  r1();
  r2();
  assert.equal(gate.stats().inflight, 0);
});

test('a request over the ceiling waits, then runs when a slot frees (FIFO)', async () => {
  const gate = new ConcurrencyGate({ maxInflight: 1, maxQueue: 4, timeoutMs: 1000 });
  const r1 = await gate.acquire();

  const order = [];
  const p2 = gate.acquire().then((r) => (order.push('a'), r));
  const p3 = gate.acquire().then((r) => (order.push('b'), r));
  await tick();
  assert.equal(gate.stats().queued, 2, 'both waiters queued behind the single slot');

  r1(); // hand the slot to the first waiter (a)
  const r2 = await p2;
  assert.deepEqual(order, ['a'], 'FIFO: first-queued served first');
  assert.equal(gate.stats().inflight, 1, 'slot transferred, not double-counted');

  r2(); // hand to b
  await p3;
  assert.deepEqual(order, ['a', 'b']);
});

test('429 when the queue is full', async () => {
  const gate = new ConcurrencyGate({ maxInflight: 1, maxQueue: 1, timeoutMs: 1000 });
  const r1 = await gate.acquire();
  const queued = gate.acquire(); // fills the 1-deep queue
  await tick();
  await assert.rejects(gate.acquire(), (e) => e instanceof GateRejection && e.statusCode === 429);
  r1();
  await queued; // drain
});

test('429 when a waiter exceeds the timeout', async () => {
  const gate = new ConcurrencyGate({ maxInflight: 1, maxQueue: 4, timeoutMs: 20 });
  const r1 = await gate.acquire();
  await assert.rejects(gate.acquire(), (e) => e instanceof GateRejection && /queued too long/.test(e.reason));
  assert.equal(gate.stats().queued, 0, 'timed-out waiter is removed from the queue');
  r1();
});

test('double release frees only one slot', async () => {
  const gate = new ConcurrencyGate({ maxInflight: 2, maxQueue: 4, timeoutMs: 1000 });
  const r1 = await gate.acquire();
  await gate.acquire();
  r1();
  r1(); // idempotent — must not drop inflight below the truly-held count
  assert.equal(gate.stats().inflight, 1);
});
