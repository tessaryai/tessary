// SPDX-License-Identifier: Apache-2.0
// A scripted local HTTP server: each request gets the next scripted response and is recorded, so
// the client tests run against real sockets and need no Tessary instance.
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { AddressInfo } from "node:net";

export interface Scripted {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
  delayMs?: number;
}

export interface Recorded {
  method: string;
  path: string;
  query: string;
  headers: IncomingMessage["headers"];
  body: unknown;
}

export class StubServer {
  readonly responses: Scripted[] = [];
  readonly requests: Recorded[] = [];
  private server: Server;

  private constructor(server: Server) {
    this.server = server;
  }

  static async start(): Promise<StubServer> {
    const stub = new StubServer(createServer());
    stub.server.on("request", (req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (chunk: Buffer) => chunks.push(chunk));
      req.on("end", () => {
        const url = new URL(req.url ?? "/", "http://stub");
        const raw = Buffer.concat(chunks).toString("utf8");
        stub.requests.push({
          method: req.method ?? "",
          path: url.pathname,
          query: url.search.replace(/^\?/, ""),
          headers: req.headers,
          body: raw ? JSON.parse(raw) : null,
        });
        const reply = stub.responses.shift() ?? { status: 500, body: "unscripted" };
        res.on("error", () => {});
        setTimeout(() => {
          if (res.destroyed) return;
          const payload = typeof reply.body === "string" ? reply.body : JSON.stringify(reply.body ?? "");
          res.writeHead(reply.status, { ...reply.headers, "content-length": Buffer.byteLength(payload) });
          res.end(payload);
        }, reply.delayMs ?? 0);
      });
    });
    await new Promise<void>((resolve) => stub.server.listen(0, "127.0.0.1", resolve));
    return stub;
  }

  get url(): string {
    const { address, port } = this.server.address() as AddressInfo;
    return `http://${address}:${port}`;
  }

  script(...responses: Scripted[]): void {
    this.responses.push(...responses);
  }

  async close(): Promise<void> {
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}

/** A PublicPrompt inside the ApiResponse envelope, as GET /v1/prompts/fetch answers it. */
export function envelope(overrides: Record<string, unknown> = {}): unknown {
  return {
    meta: { status: 200, success: true },
    data: {
      id: "pv_1",
      name: "team/a/b",
      version: 3,
      type: "text",
      prompt: "Hello {{ name }}",
      config: { temperature: 0.2 },
      labels: ["production", "latest"],
      tags: ["onboarding"],
      commitMessage: "warmer tone",
      createdAt: "2026-10-18T09:12:00Z",
      updatedAt: "2026-10-18T09:12:00Z",
      isActive: true,
      ...overrides,
    },
  };
}

export function apiError(status: number, code: string, message: string): unknown {
  return { meta: { status, success: false, error: { code, message } } };
}

/** A clock the test moves by hand, in milliseconds. */
export class Clock {
  now = 0;
  readonly read = (): number => this.now;
}

/** Records every sleep and returns at once. */
export class Sleeps {
  readonly calls: number[] = [];
  readonly sleep = async (ms: number): Promise<void> => {
    this.calls.push(ms);
  };
}
