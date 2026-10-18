// SPDX-License-Identifier: Apache-2.0
/*
 * The widget read limiter and the query factories. The bugs worth catching: a fifth read that starts while four are in
 * flight, a queue that drains out of order or freezes after a refused read, a BUSY refusal retried forever or never,
 * and a Triage pulse that asks for a different slice than the default dashboard's Spend tile.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { api } from "../../test/api";
import { DEFAULT_DASHBOARD, METRICS_CATALOG } from "../../test/metricsFixtures";
import {
  catalogQuery, dashboardQuery, dashboardsQuery, isBusy, Limiter, MAX_IN_FLIGHT, pulseQueries, widgetLimiter, widgetQuery,
} from "./metricsQueries";
import { viewOf, widgetRequest } from "./metricsRules";
import type { MetricsQueryRequest } from "./wire";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

afterEach(() => widgetLimiter.reset());

type Deferred = { promise: Promise<string>; resolve: (v: string) => void; reject: (e: unknown) => void };
function deferred(): Deferred {
  let resolve!: (v: string) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<string>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise((r) => setTimeout(r, 0));
const BUSY = new ApiError(503, { code: "METRICS.BUSY", message: "Metrics is busy." });
const TIMED_OUT = new ApiError(504, { code: "METRICS.TIMED_OUT", message: "Query took too long." });
// 2026-10-18T09:58:48Z: the 7-day window ends at the start of the next hour.
const NOW = Date.parse("2026-10-18T09:58:48Z");

describe("Limiter", () => {
  // Bug: nine widgets fire nine reads at once and the server's gate of three refuses six of them.
  it("theLimiterRunsAtMostFourAndDrainsInOrder", async () => {
    const limiter = new Limiter(MAX_IN_FLIGHT);
    const work = Array.from({ length: 6 }, deferred);
    const started: number[] = [];
    const done = work.map((d, i) =>
      limiter.run(() => {
        started.push(i);
        return d.promise;
      }),
    );
    expect(MAX_IN_FLIGHT).toBe(4);
    expect(started).toEqual([0, 1, 2, 3]);
    expect([limiter.active, limiter.waiting]).toEqual([4, 2]);

    work[2].resolve("c");
    await flush();
    expect(started).toEqual([0, 1, 2, 3, 4]);
    expect([limiter.active, limiter.waiting]).toEqual([4, 1]);

    work[0].resolve("a");
    await flush();
    expect(started).toEqual([0, 1, 2, 3, 4, 5]);

    for (const d of work) d.resolve("x");
    await expect(done[2]).resolves.toBe("c");
    await Promise.all(done);
    expect([limiter.active, limiter.waiting]).toEqual([0, 0]);
  });

  // Bug: a refused read keeps its slot, so after four refusals every later widget waits forever.
  it("theLimiterReleasesOnRejection", async () => {
    const limiter = new Limiter(1);
    const first = deferred();
    const failed = limiter.run(() => first.promise);
    const next = limiter.run(() => Promise.resolve("next"));
    expect(limiter.waiting).toBe(1);

    first.reject(BUSY);
    await expect(failed).rejects.toBe(BUSY);
    await expect(next).resolves.toBe("next");
    expect(limiter.active).toBe(0);
  });

  // Bug: a test that leaves four reads pending freezes every later read in its file.
  it("resetDropsTheQueueAndTheActiveCount", async () => {
    const limiter = new Limiter(1);
    void limiter.run(() => new Promise<never>(() => {}));
    let queuedRan = false;
    void limiter.run(async () => {
      queuedRan = true;
    });
    expect([limiter.active, limiter.waiting]).toEqual([1, 1]);

    limiter.reset();
    expect([limiter.active, limiter.waiting]).toEqual([0, 0]);
    await expect(limiter.run(() => Promise.resolve("fresh"))).resolves.toBe("fresh");
    expect(queuedRan).toBe(false);
  });
});

describe("query factories", () => {
  const req: MetricsQueryRequest = {
    view: "llm_calls", measures: ["cost_usd"], dimension: null, filters: null, from: "2026-10-11T00:00:00Z",
    to: "2026-10-18T00:00:00Z", granularity: null, top_n: null, compare: null,
  };

  // Bug: two widgets with different requests share one cache entry, or a BUSY read is retried forever or never.
  it("widgetQueryKeysCarryTheRequestAndRetryBusyOnce", async () => {
    const q = widgetQuery(api as never, req);
    expect(q.queryKey).toEqual(["metrics", "query", api.base, req]);
    const retry = q.retry as (count: number, err: unknown) => boolean;
    expect(retry(0, BUSY)).toBe(true);
    expect(retry(1, BUSY)).toBe(false);
    expect(retry(0, TIMED_OUT)).toBe(false);
    expect(q.retryDelay).toBe(1500);
    expect([isBusy(BUSY), isBusy(TIMED_OUT), isBusy(undefined)]).toEqual([true, false, false]);

    const result = { view: "llm_calls" };
    api.queryMetrics.mockResolvedValue(result);
    await expect((q.queryFn as () => Promise<unknown>)()).resolves.toBe(result);
    expect(api.queryMetrics).toHaveBeenCalledWith(req);
  });

  // Bug: a project switch serves the last project's catalog or dashboards from the cache.
  it("theReadKeysCarryTheProjectBaseAndCallTheirEndpoints", async () => {
    api.getMetricsCatalog.mockResolvedValue(METRICS_CATALOG);
    api.listMetricDashboards.mockResolvedValue({ dashboards: [], can_manage: true });
    api.getMetricDashboard.mockResolvedValue(DEFAULT_DASHBOARD);

    const catalog = catalogQuery(api as never);
    const list = dashboardsQuery(api as never);
    const one = dashboardQuery(api as never, "01JAX");
    expect([catalog.queryKey, list.queryKey, one.queryKey]).toEqual([
      ["metrics", "catalog", api.base],
      ["metrics", "dashboards", api.base],
      ["metrics", "dashboard", api.base, "01JAX"],
    ]);
    expect(catalog.staleTime).toBe(300_000);
    await expect((catalog.queryFn as () => Promise<unknown>)()).resolves.toBe(METRICS_CATALOG);
    await (list.queryFn as () => Promise<unknown>)();
    await (one.queryFn as () => Promise<unknown>)();
    expect(api.getMetricDashboard).toHaveBeenCalledWith("01JAX");
    expect(api.listMetricDashboards).toHaveBeenCalledTimes(1);
  });

  // Bug: Triage prints a spend or p95 figure from a different slice than the Metrics tiles it links to.
  it("pulseQueriesBuildTheTwoSevenDayRequests", async () => {
    api.queryMetrics.mockResolvedValue({});
    const pulse = pulseQueries(api as never, NOW);
    const to = "2026-10-18T10:00:00.000Z";
    const window = { from: "2026-10-11T10:00:00.000Z", to };
    const spendTile = DEFAULT_DASHBOARD.definition.widgets.find((w) => w.id === "spend")!;

    expect(pulse.spend.queryKey).toEqual(["metrics", "pulse", api.base, "spend", to]);
    expect(pulse.p95.queryKey).toEqual(["metrics", "pulse", api.base, "p95", to]);
    expect([pulse.spend.staleTime, pulse.p95.staleTime]).toEqual([60_000, 60_000]);

    await (pulse.spend.queryFn as () => Promise<unknown>)();
    await (pulse.p95.queryFn as () => Promise<unknown>)();
    const [spendReq, p95Req] = api.queryMetrics.mock.calls.map((c) => c[0]);
    expect(JSON.stringify(spendReq)).toBe(
      JSON.stringify(widgetRequest(spendTile, window, null, viewOf(METRICS_CATALOG, "llm_calls"))),
    );
    expect(p95Req).toEqual({
      view: "traces", measures: ["latency_p95_ms"], dimension: null, filters: null, ...window, granularity: null,
      top_n: null, compare: "previous_window",
    });
  });
});
