// SPDX-License-Identifier: Apache-2.0
// Triage imports pulseQueries from here, so this file must never import the grid or anything that loads it.
import { keepPreviousData, queryOptions } from "@tanstack/react-query";
import type { ProjectApi } from "../../api/client";
import type { ApiError } from "../../api/types";
import { resolveWindow } from "./metricsRules";
import type { MetricsQueryRequest } from "./wire";

/** The server admits three metrics reads per project at once; four in flight keeps one queued there, not six. */
export const MAX_IN_FLIGHT = 4;

/** FIFO: a settled read, kept or refused, starts the next one; an aborted read still waiting leaves without running. */
export class Limiter {
  private running = 0;
  private queue: (() => void)[] = [];

  constructor(private readonly max: number) {}

  get active(): number {
    return this.running;
  }

  get waiting(): number {
    return this.queue.length;
  }

  run<T>(work: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const start = () => {
        signal?.removeEventListener("abort", leave);
        this.running++;
        work()
          .then(resolve, reject)
          .finally(() => {
            this.running--;
            this.queue.shift()?.();
          });
      };
      const leave = () => {
        this.queue = this.queue.filter((q) => q !== start);
        reject(signal!.reason);
      };
      if (this.running < this.max) {
        start();
      } else {
        this.queue.push(start);
        signal?.addEventListener("abort", leave, { once: true });
      }
    });
  }

  /** For tests: a read left pending on purpose would otherwise hold its slot for the rest of the file. */
  reset(): void {
    this.queue = [];
    this.running = 0;
  }
}

export const widgetLimiter = new Limiter(MAX_IN_FLIGHT);

export const catalogQuery = (api: ProjectApi) =>
  queryOptions({ queryKey: ["metrics", "catalog", api.base], queryFn: () => api.getMetricsCatalog(), staleTime: 5 * 60_000 });

export const dashboardsQuery = (api: ProjectApi) =>
  queryOptions({ queryKey: ["metrics", "dashboards", api.base], queryFn: () => api.listMetricDashboards() });

export const dashboardQuery = (api: ProjectApi, id: string) =>
  queryOptions({ queryKey: ["metrics", "dashboard", api.base, id], queryFn: () => api.getMetricDashboard(id) });

export const isBusy = (err: unknown) => (err as ApiError | undefined)?.code === "METRICS.BUSY";

export const widgetQuery = (api: ProjectApi, req: MetricsQueryRequest) =>
  queryOptions({
    queryKey: ["metrics", "query", api.base, req],
    queryFn: ({ signal }) => widgetLimiter.run(() => api.queryMetrics(req, signal), signal),
    placeholderData: keepPreviousData,
    retry: (count: number, err: unknown) => count < 1 && isBusy(err),
    retryDelay: 1500,
  });

/** The default dashboard's Spend and p95 tiles over the last 7 days, byte for byte, so Triage and Metrics agree. */
export const pulseQueries = (api: ProjectApi, now: number) => {
  const window = resolveWindow({ kind: "preset", key: "7d" }, now);
  const base = {
    dimension: null, filters: null, from: window.from, to: window.to, granularity: null, top_n: null,
    compare: "previous_window",
  } as const;
  const spendReq: MetricsQueryRequest = {
    view: "llm_calls", measures: ["cost_usd", "usd_per_trace"], ...base,
  };
  const p95Req: MetricsQueryRequest = { view: "traces", measures: ["latency_p95_ms"], ...base };
  return {
    spend: queryOptions({
      queryKey: ["metrics", "pulse", api.base, "spend", window.to],
      queryFn: () => api.queryMetrics(spendReq),
      staleTime: 60_000,
    }),
    p95: queryOptions({
      queryKey: ["metrics", "pulse", api.base, "p95", window.to],
      queryFn: () => api.queryMetrics(p95Req),
      staleTime: 60_000,
    }),
  };
};
