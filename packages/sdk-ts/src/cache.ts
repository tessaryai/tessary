// SPDX-License-Identifier: Apache-2.0
// In-process prompt cache: a fresh entry is served, a stale entry is served at once and refreshed
// in the background (one refresh per key), and concurrent misses on one key share one load when they
// ask for the same per-call retries and timeout. A write
// bumps its name's generation and a clear bumps the cache's epoch, so a load or refresh that started
// before either stores nothing.
import { PromptNotFoundError } from "./errors.ts";
import type { ChatPrompt, TextPrompt } from "./prompts.ts";

export type Prompt = TextPrompt | ChatPrompt;

export interface PromptsLogger {
  debug(message: string, fields: Record<string, unknown>): void;
}

interface Entry {
  value: Prompt;
  fetchedAt: number;
}

/** The prompt name of a cache key; names cannot hold `|`. */
function nameOf(key: string): string {
  const bar = key.indexOf("|");
  return bar < 0 ? key : key.slice(0, bar);
}

export class PromptCache {
  readonly #clock: () => number;
  readonly #logger: PromptsLogger;
  readonly #entries = new Map<string, Entry>();
  readonly #loading = new Map<string, Promise<Prompt>>();
  readonly #refreshing = new Map<string, Promise<void>>();
  readonly #generations = new Map<string, number>();
  #epoch = 0;

  /** `clock` returns milliseconds; TTLs are in seconds. */
  constructor(clock: () => number, logger: PromptsLogger) {
    this.#clock = clock;
    this.#logger = logger;
  }

  static key(name: string, version: number | undefined, label: string | undefined): string {
    if (version !== undefined) return `${name}|version:${version}`;
    return `${name}|label:${label || "production"}`;
  }

  /**
   * `share` names the caller's per-call retries and timeout: a cold miss joins only a load started
   * with the same ones, so no caller waits on a load slower than it asked for.
   */
  async get(key: string, ttlSeconds: number, loader: () => Promise<Prompt>, share?: string): Promise<Prompt> {
    if (ttlSeconds === 0) return loader();
    const entry = this.#entries.get(key);
    // The suffix keeps the name prefix, so evictName still drops the load.
    if (entry === undefined) return this.#load(key, share === undefined ? key : `${key}|${share}`, loader);
    if (this.#clock() - entry.fetchedAt >= ttlSeconds * 1000 && !this.#refreshing.has(key)) {
      this.#refreshing.set(key, this.#refresh(key, loader));
    }
    return entry.value;
  }

  #load(key: string, loadKey: string, loader: () => Promise<Prompt>): Promise<Prompt> {
    const pending = this.#loading.get(loadKey);
    if (pending !== undefined) return pending;
    const stamp = this.#stamp(key);
    // Declared first so `finally` can compare the slot with this very promise.
    let load!: Promise<Prompt>;
    load = (async () => {
      try {
        await undefined; // yield first, so `finally` never runs before the map holds this promise
        const value = await loader();
        this.#store(key, stamp, value);
        return value;
      } finally {
        // A write may have dropped this load and a newer one may hold the slot.
        if (this.#loading.get(loadKey) === load) this.#loading.delete(loadKey);
      }
    })();
    this.#loading.set(loadKey, load);
    return load;
  }

  #refresh(key: string, loader: () => Promise<Prompt>): Promise<void> {
    const stamp = this.#stamp(key);
    let refresh!: Promise<void>;
    refresh = (async () => {
      try {
        await undefined; // yield first, so `finally` never runs before the map holds this promise
        this.#store(key, stamp, await loader());
      } catch (error) {
        // A refresh never rejects: a 404 evicts, anything else keeps the stale entry for a later retry.
        if (error instanceof PromptNotFoundError) this.#entries.delete(key);
        else this.#logger.debug("prompt refresh failed", { key, error: (error as Error).name });
      } finally {
        if (this.#refreshing.get(key) === refresh) this.#refreshing.delete(key);
      }
    })();
    return refresh;
  }

  /** Stores only when no write to the name and no clear happened since the load started. */
  #store(key: string, stamp: string, value: Prompt): void {
    if (this.#stamp(key) === stamp) this.#entries.set(key, { value, fetchedAt: this.#clock() });
  }

  #stamp(key: string): string {
    return `${this.#epoch}:${this.#generations.get(nameOf(key)) ?? 0}`;
  }

  #bump(name: string): void {
    this.#generations.set(name, (this.#generations.get(name) ?? 0) + 1);
  }

  evict(key: string): void {
    this.#bump(nameOf(key));
    this.#entries.delete(key);
  }

  /**
   * Drop every entry, load and refresh for `name`, so nothing read before a write is served or stored
   * after it; names cannot hold `|`, so `team/a` never matches `team/ab`.
   */
  evictName(name: string): void {
    this.#bump(name);
    const prefix = `${name}|`;
    for (const map of [this.#entries, this.#loading, this.#refreshing]) {
      for (const key of [...map.keys()]) {
        if (key.startsWith(prefix)) map.delete(key);
      }
    }
  }

  /** Drop everything, and stop every load and refresh in flight from storing its value. */
  clear(): void {
    this.#epoch++;
    this.#entries.clear();
    this.#loading.clear();
    this.#refreshing.clear();
  }

  size(): number {
    return this.#entries.size;
  }

  /** Settles once every load and refresh in flight has settled. For tests; not exported. */
  async whenIdle(): Promise<void> {
    await Promise.allSettled([...this.#loading.values(), ...this.#refreshing.values()]);
  }
}
