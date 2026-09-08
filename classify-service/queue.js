// SPDX-License-Identifier: Apache-2.0
'use strict';
/*
 * Bounded concurrency gate for /classify.
 *
 * Inference is CPU- and memory-bound, so at most MAX_INFLIGHT requests may run at once — the
 * memory-safety ceiling that keeps the service inside its task envelope (the 2026-07-12 OOM was
 * concurrent inference spiking activation memory past the cap). The previous behavior was a hard
 * reject at that ceiling (429 immediately, backend retries next heartbeat).
 *
 * This adds a bounded FIFO WAIT in front of the ceiling: a request over the concurrency limit
 * waits for a slot instead of failing outright, which smooths sweep bursts — and matters more once
 * sliding-window scoring makes each request longer (more window forward passes, so slots are held
 * longer and transient contention is more likely). Requests still fail fast (429) when the service
 * is genuinely saturated: the queue is full (MAX_QUEUE) or a waiter exceeds QUEUE_TIMEOUT_MS. The
 * concurrency ceiling itself never moves, so memory stays bounded regardless of load.
 */

/** A 429-worthy rejection: the service is saturated, the caller should retry later. */
class GateRejection extends Error {
  constructor(reason) {
    super(reason);
    this.name = 'GateRejection';
    this.reason = reason;
    this.statusCode = 429;
  }
}

class ConcurrencyGate {
  /** @param {{maxInflight:number, maxQueue:number, timeoutMs:number}} opts */
  constructor({ maxInflight, maxQueue, timeoutMs }) {
    this.maxInflight = Math.max(1, maxInflight);
    this.maxQueue = Math.max(0, maxQueue);
    this.timeoutMs = timeoutMs;
    this.inflight = 0;
    this.queue = []; // FIFO of { resolve, reject, timer }
  }

  /**
   * Acquire a slot. Resolves with a `release()` function the caller MUST invoke (in a finally),
   * or rejects with a {@link GateRejection} (→ HTTP 429) when the service is saturated.
   */
  acquire() {
    if (this.inflight < this.maxInflight) {
      this.inflight += 1;
      return Promise.resolve(this._releaser());
    }
    if (this.queue.length >= this.maxQueue) {
      return Promise.reject(new GateRejection('at capacity (queue full)'));
    }
    return new Promise((resolve, reject) => {
      const waiter = { resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const i = this.queue.indexOf(waiter);
        if (i >= 0) this.queue.splice(i, 1);
        reject(new GateRejection('queued too long, retry later'));
      }, this.timeoutMs);
      // Do not keep the event loop alive solely for a queued waiter's timer.
      if (typeof waiter.timer.unref === 'function') waiter.timer.unref();
      this.queue.push(waiter);
    });
  }

  /** A single-use release: hand the freed slot to the next waiter, or lower inflight. */
  _releaser() {
    let released = false;
    return () => {
      if (released) return; // idempotent — a double release must not free two slots
      released = true;
      const next = this.queue.shift();
      if (next) {
        clearTimeout(next.timer);
        // Slot transfers straight to the waiter — inflight stays at the ceiling, no gap.
        next.resolve(this._releaser());
      } else {
        this.inflight -= 1;
      }
    };
  }

  /** Snapshot for /healthz-style observability and tests. */
  stats() {
    return { inflight: this.inflight, queued: this.queue.length };
  }
}

module.exports = { ConcurrencyGate, GateRejection };
