// SPDX-License-Identifier: Apache-2.0
// The HTTP leg: global fetch only, bounded retries, and no redirect ever followed.
import type { PromptsLogger } from "./cache.ts";
import { PromptApiError, PromptNotFoundError, PromptTransportError, type TessaryPromptsError } from "./errors.ts";

export const VERSION = "0.1.0";
const RETRY_AFTER_CAP_SECONDS = 30;
const JITTER_MS = 250;

/** The prompt a request is about, for PromptNotFoundError. */
export interface PromptRef {
  name: string;
  version: number | null;
  label: string | null;
}

export interface TransportOptions {
  host: string;
  apiKey: string;
  timeoutMs: number;
  maxRetries: number;
  backoffMs: number;
  fetch: typeof globalThis.fetch;
  sleep: (ms: number) => Promise<void>;
  random: () => number;
  logger: PromptsLogger;
}

/** Code and message from either error shape: the envelope's meta.error, or a filter's error. */
function errorParts(status: number, text: string, statusText: string): [string, string] {
  try {
    const doc = JSON.parse(text);
    const { code, message } = doc.meta?.error ?? doc.error;
    return [String(code), String(message)];
  } catch {
    return [`http_${status}`, statusText];
  }
}

export class HttpTransport {
  readonly #options: TransportOptions;

  constructor(options: TransportOptions) {
    this.#options = options;
  }

  #waitMs(retryAfter: string | null): number {
    if (/^\d+$/.test(retryAfter ?? "")) return Math.min(Number(retryAfter), RETRY_AFTER_CAP_SECONDS) * 1000;
    return this.#options.backoffMs + this.#options.random() * JITTER_MS;
  }

  /** Send one call with retries and return the envelope's `data` object. */
  async request(
    method: "GET" | "POST" | "PATCH",
    path: string,
    ref: PromptRef,
    { query, body }: { query?: URLSearchParams; body?: Record<string, unknown> },
  ): Promise<Record<string, unknown>> {
    const o = this.#options;
    const url = `${o.host}${path}${query ? `?${query}` : ""}`;
    const headers: Record<string, string> = {
      Authorization: `Bearer ${o.apiKey}`,
      Accept: "application/json",
      "User-Agent": `tessary-prompts-ts/${VERSION}`,
    };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const isRead = method === "GET";
    const attempts = 1 + o.maxRetries;
    let attempt = 0;
    let error!: TessaryPromptsError;
    let retryable = false;
    let cause: number | string = "";
    let retryAfter: string | null = null;
    do {
      if (attempt > 0) {
        o.logger.debug("retrying", { method, path, cause, attempt, attempts });
        await o.sleep(this.#waitMs(retryAfter));
      }
      attempt++;
      retryAfter = null;
      let response: Response;
      let text: string;
      try {
        // "manual" hands back the 3xx itself, so the bearer key is never sent to the Location.
        response = await o.fetch(url, {
          method,
          headers,
          body: body === undefined ? undefined : JSON.stringify(body),
          redirect: "manual",
          signal: AbortSignal.timeout(o.timeoutMs),
        });
        text = await response.text();
      } catch (failure) {
        // fetch rejects with a TypeError on a network failure and a DOMException on the timeout.
        cause = (failure as Error).name;
        error = new PromptTransportError(`${method} ${path} failed: ${cause}`);
        retryable = isRead;
        continue;
      }
      const status = response.status;
      if (status >= 200 && status < 300) return parseData(text);
      if (status === 404) {
        throw new PromptNotFoundError(errorParts(status, text, "not found")[1], ref.name, ref.version, ref.label);
      }
      if (status < 400 || status >= 600) {
        throw new PromptTransportError(`unexpected HTTP ${status}; redirects are not followed`);
      }
      error = new PromptApiError(status, ...errorParts(status, text, response.statusText));
      retryable = status === 429 || (status >= 500 && isRead);
      cause = status;
      retryAfter = response.headers.get("retry-after");
    } while (retryable && attempt < attempts);
    throw error;
  }
}

function parseData(text: string): Record<string, unknown> {
  try {
    const data: unknown = JSON.parse(text).data;
    if (typeof data === "object" && data !== null) return data as Record<string, unknown>;
  } catch {
    // fall through to the transport error
  }
  throw new PromptTransportError("the server answered 2xx with an unreadable body");
}
