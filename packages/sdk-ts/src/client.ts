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

const NOOP_LOGGER: PromptsLogger = { debug: () => {} };
const realSleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

function checkName(name: string): void {
  if (!name) throw new TypeError("name must not be empty");
}

function checkVersion(version: number): void {
  if (!Number.isInteger(version) || version < 1) throw new TypeError("version must be an integer of at least 1");
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
    this.#ttlSeconds = ttl;
    const logger = options.logger ?? NOOP_LOGGER;
    this.#transport = new HttpTransport({
      host: options.host.replace(/\/+$/, ""),
      apiKey: options.apiKey,
      timeoutMs: options.timeoutMs ?? 5000,
      maxRetries,
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
   * stale cached prompt is returned at once and refreshed in the background.
   */
  async get(name: string, options: GetPromptOptions = {}): Promise<Prompt> {
    const { version, label, cacheTtlSeconds, fallback } = options;
    checkName(name);
    if (version !== undefined) checkVersion(version);
    if (version !== undefined && label) throw new TypeError("give version or label, not both");
    if (cacheTtlSeconds !== undefined && cacheTtlSeconds < 0) throw new TypeError("cacheTtlSeconds must not be negative");
    if (fallback !== undefined && typeof fallback !== "string" && !Array.isArray(fallback)) {
      throw new TypeError("fallback must be a string (text) or an array of messages (chat)");
    }
    const query = new URLSearchParams({ name });
    if (version !== undefined) query.set("version", String(version));
    if (label) query.set("label", label);
    const key = PromptCache.key(name, version, label);
    const ref = { name, version: version ?? null, label: label || null };
    const load = async () => fromData(await this.#transport.request("GET", "/v1/prompts/fetch", ref, { query }));
    try {
      return await this.cache.get(key, cacheTtlSeconds ?? this.#ttlSeconds, load);
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
    const body = {
      name: input.name,
      type,
      prompt: input.prompt,
      ...Object.fromEntries(Object.entries(optional).filter(([, value]) => value !== undefined)),
    };
    const data = await this.#transport.request("POST", "/v1/prompts", { name: input.name, version: null, label: null }, { body });
    this.cache.evictName(input.name);
    return fromData(data);
  }

  /** Add labels to a version, moving each one off any other version. Needs an admin key. */
  async updateLabels(name: string, version: number, newLabels: string[]): Promise<Prompt> {
    checkName(name);
    checkVersion(version);
    const body = { name, version, newLabels };
    const data = await this.#transport.request("PATCH", "/v1/prompts/labels", { name, version, label: null }, { body });
    this.cache.evictName(name);
    return fromData(data);
  }
}
