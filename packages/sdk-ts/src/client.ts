// SPDX-License-Identifier: Apache-2.0
// The client: read through the cache, fall back when asked, and write through the public API.
import { PromptCache, type Prompt, type PromptsLogger } from "./cache.ts";
import { PromptNotFoundError } from "./errors.ts";
import { HttpTransport } from "./http.ts";
import { fallbackPrompt, fromData, type ChatItem } from "./prompts.ts";

export interface TessaryPromptsOptions {
  /** The instance origin, for example `https://tessary.example.com`. */
  host: string;
  /** A query (`tsy_q_`) key reads; an admin (`tsy_a_`) key reads and writes. */
  apiKey: string;
  timeoutMs?: number;
  cacheTtlSeconds?: number;
  maxRetries?: number;
  retryBackoffMs?: number;
  fetch?: typeof globalThis.fetch;
  /** Milliseconds; `Date.now` by default. */
  clock?: () => number;
  sleep?: (ms: number) => Promise<void>;
  random?: () => number;
  logger?: PromptsLogger;
}

export interface GetPromptOptions {
  version?: number;
  label?: string;
  cacheTtlSeconds?: number;
  /** Returned instead of throwing when the fetch fails and nothing is cached. */
  fallback?: string | ChatItem[];
  /** Retries for this call, 0 to 4; the client's value when absent. */
  maxRetries?: number;
  /** Timeout of each attempt of this call; the client's value when absent. */
  fetchTimeoutMs?: number;
}

export interface UpdatePromptInput {
  name: string;
  version: number;
  /** Added to the version, and moved off any other version that holds them. */
  newLabels?: string[];
  /** Taken off the version, after `newLabels`. */
  removeLabels?: string[];
}

export interface DeletePromptOptions {
  version?: number;
  label?: string;
}

export interface ListPromptsOptions {
  name?: string;
  label?: string;
  tag?: string;
  page?: number;
  limit?: number;
  /** ISO-8601 instants that bound when each prompt last changed. */
  fromUpdatedAt?: string;
  toUpdatedAt?: string;
}

export interface PromptListItem {
  name: string;
  type: "text" | "chat";
  versions: number[];
  labels: string[];
  tags: string[];
  lastUpdatedAt: string;
  lastConfig: Record<string, unknown>;
}

export interface PromptListPage {
  data: PromptListItem[];
  meta: { page: number; limit: number; totalItems: number; totalPages: number };
}

export interface CreatePromptInput {
  name: string;
  prompt: string | ChatItem[];
  type?: "text" | "chat";
  config?: Record<string, unknown>;
  labels?: string[];
  tags?: string[];
  commitMessage?: string;
}

const MAX_RETRIES_CAP = 4;
const NOOP_LOGGER: PromptsLogger = { debug: () => {} };
const realSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function checkName(name: string): void {
  if (!name) throw new TypeError("name must not be empty");
}

function checkVersion(version: number): void {
  if (!Number.isInteger(version) || version < 1) throw new TypeError("version must be an integer of at least 1");
}

function boundedRetries(maxRetries: number): number {
  return Math.max(0, Math.min(maxRetries, MAX_RETRIES_CAP));
}

/** Drops absent values, so an optional the caller left out is never sent as null. */
function present<T extends object>(record: T): Partial<T> {
  return Object.fromEntries(Object.entries(record).filter(([, value]) => value !== undefined)) as Partial<T>;
}

export class TessaryPrompts {
  readonly cache: PromptCache;
  readonly #transport: HttpTransport;
  readonly #ttlSeconds: number;

  constructor(options: TessaryPromptsOptions) {
    if (!/^https?:\/\//.test(options.host)) throw new TypeError("host must start with http:// or https://");
    if (!options.apiKey) throw new TypeError("apiKey must not be empty");
    const ttl = options.cacheTtlSeconds ?? 60;
    const maxRetries = options.maxRetries ?? 2;
    if (ttl < 0 || maxRetries < 0) throw new TypeError("cacheTtlSeconds and maxRetries must not be negative");
    const timeoutMs = options.timeoutMs ?? 5000;
    if (!(timeoutMs > 0)) throw new TypeError("timeoutMs must be positive");
    this.#ttlSeconds = ttl;
    const logger = options.logger ?? NOOP_LOGGER;
    this.#transport = new HttpTransport({
      host: options.host.replace(/\/+$/, ""),
      apiKey: options.apiKey,
      timeoutMs,
      maxRetries: boundedRetries(maxRetries),
      backoffMs: options.retryBackoffMs ?? 500,
      fetch: options.fetch ?? globalThis.fetch,
      sleep: options.sleep ?? realSleep,
      random: options.random ?? Math.random,
      logger,
    });
    this.cache = new PromptCache(options.clock ?? Date.now, logger);
  }

  /**
   * Return the version by number, by label, or the `production` label when neither is given. A
   * stale cached prompt is returned at once and refreshed in the background. `maxRetries` (0 to 4)
   * and `fetchTimeoutMs` override the client's values for this call.
   */
  async get(name: string, options: GetPromptOptions = {}): Promise<Prompt> {
    const { version, label, cacheTtlSeconds, fallback, maxRetries, fetchTimeoutMs } = options;
    checkName(name);
    if (version !== undefined) checkVersion(version);
    if (version !== undefined && label) throw new TypeError("give version or label, not both");
    if (cacheTtlSeconds !== undefined && cacheTtlSeconds < 0) throw new TypeError("cacheTtlSeconds must not be negative");
    if (fallback !== undefined && typeof fallback !== "string" && !Array.isArray(fallback)) {
      throw new TypeError("fallback must be a string (text) or an array of messages (chat)");
    }
    if (fetchTimeoutMs !== undefined && !(fetchTimeoutMs > 0)) throw new TypeError("fetchTimeoutMs must be positive");
    const perCall = {
      maxRetries: maxRetries === undefined ? undefined : boundedRetries(maxRetries),
      timeoutMs: fetchTimeoutMs,
    };
    const query = new URLSearchParams({ name });
    if (version !== undefined) query.set("version", String(version));
    if (label) query.set("label", label);
    const key = PromptCache.key(name, version, label);
    const ref = { name, version: version ?? null, label: label || null };
    const load = async () => fromData(await this.#transport.request("GET", "/v1/prompts/fetch", ref, { query, ...perCall }));
    // Callers on the client's values share one cold load; a caller with its own shares only with its peers.
    const share =
      perCall.maxRetries === undefined && fetchTimeoutMs === undefined ? undefined : `${perCall.maxRetries}:${fetchTimeoutMs}`;
    try {
      return await this.cache.get(key, cacheTtlSeconds ?? this.#ttlSeconds, load, share);
    } catch (error) {
      if (error instanceof PromptNotFoundError) this.cache.evict(key);
      if (fallback === undefined) throw error;
      return fallbackPrompt(name, fallback);
    }
  }

  /** Create a prompt, or a new version of an existing one. Needs an admin key. */
  async create(input: CreatePromptInput): Promise<Prompt> {
    const type = input.type ?? "text";
    checkName(input.name);
    if (type !== "text" && type !== "chat") throw new TypeError("type must be text or chat");
    if (!(type === "text" ? typeof input.prompt === "string" : Array.isArray(input.prompt))) {
      throw new TypeError("a text prompt is a string and a chat prompt is an array of messages");
    }
    const optional = { config: input.config, labels: input.labels, tags: input.tags, commitMessage: input.commitMessage };
    const body = { name: input.name, type, prompt: input.prompt, ...present(optional) };
    const data = await this.#transport.request("POST", "/v1/prompts", { name: input.name, version: null, label: null }, { body });
    this.cache.evictName(input.name);
    return fromData(data);
  }

  /**
   * Add `newLabels` to a version, moving each one off any other version, then take `removeLabels`
   * off it. Needs an admin key.
   */
  async update(input: UpdatePromptInput): Promise<Prompt> {
    const { name, version } = input;
    checkName(name);
    checkVersion(version);
    const body = { name, version, ...present({ newLabels: input.newLabels, removeLabels: input.removeLabels }) };
    const data = await this.#transport.request("PATCH", "/v1/prompts/labels", { name, version, label: null }, { body });
    this.cache.evictName(name);
    return fromData(data);
  }

  /**
   * Delete one version by number, the version a label points at, or with neither the whole prompt.
   * Deleting the last version deletes the prompt. Needs an admin key; never retried.
   */
  async delete(name: string, options: DeletePromptOptions = {}): Promise<boolean> {
    const { version, label } = options;
    checkName(name);
    if (version !== undefined) checkVersion(version);
    if (version !== undefined && label) throw new TypeError("give version or label, not both");
    const query = new URLSearchParams({ name });
    if (version !== undefined) query.set("version", String(version));
    if (label) query.set("label", label);
    const ref = { name, version: version ?? null, label: label || null };
    const data = await this.#transport.request("DELETE", "/v1/prompts", ref, { query });
    this.cache.evictName(name);
    return data.deleted === true;
  }

  /** One page of the project's prompts, ordered by name. Never cached. */
  async list(options: ListPromptsOptions = {}): Promise<PromptListPage> {
    const params = present({
      name: options.name,
      label: options.label,
      tag: options.tag,
      page: options.page,
      limit: options.limit,
      fromUpdatedAt: options.fromUpdatedAt,
      toUpdatedAt: options.toUpdatedAt,
    });
    const query = new URLSearchParams(Object.entries(params).map(([key, value]): [string, string] => [key, String(value)]));
    const ref = { name: options.name ?? "", version: null, label: options.label ?? null };
    return (await this.#transport.request("GET", "/v1/prompts", ref, { query })) as unknown as PromptListPage;
  }

  /** Drop every cached prompt, so the next read of each one goes to the server. */
  clearCache(): void {
    this.cache.clear();
  }
}
