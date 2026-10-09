// SPDX-License-Identifier: Apache-2.0
/*
 * Vitals. A roll-up, never a comparison: the bugs worth catching are a delta or prior-window figure
 * leaking back onto the page, a total printed wrong, a sort that orders strings as numbers or puts a
 * missing value first, a row that opens the wrong call site's traces (or any traces for the
 * unattributed row or a model), and a window or grouping switch that reads the wrong slice.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Vitals as VitalsData, VitalsGroup } from "../../api/types";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { Vitals } from "./Vitals";

const api = vi.hoisted(() => ({ base: "/api/orgs/acme/projects/default", getVitals: vi.fn() }));

vi.mock("../../tenant/TenantContext", () => ({
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
}));

const group = (
  key: string | null,
  usd: number,
  over: { cost?: Partial<VitalsGroup["cost"]>; duration?: Partial<VitalsGroup["duration"]>; label?: string | null } = {},
): VitalsGroup => ({
  key,
  label: over.label ?? key,
  flagged: false,
  cost: {
    usd,
    baseline_usd: null,
    calls: 10,
    tokens: 1000,
    unpriced_calls: 0,
    delta_pct_per_turn: null,
    usd_per_turn: null,
    flagged: false,
    ...over.cost,
  },
  duration: {
    p50_ms: null,
    p95_ms: null,
    baseline_p95_ms: null,
    delta_pct: null,
    turns: 5,
    unterminated: 0,
    flagged: false,
    ...over.duration,
  },
});

const window7 = { days: 7, from: "", to: "", baseline_from: "", baseline_to: "" };

const DATA: VitalsData = {
  dimension: "call_site",
  priced_models: 3,
  window: window7,
  total: group(null, 512.4, {
    // The deltas and flags are set so a page that started printing them again would show them.
    cost: {
      baseline_usd: 400,
      delta_pct_per_turn: 28.4,
      flagged: true,
      unpriced_calls: 1234,
      usd_per_turn: 0.0427,
      calls: 3400,
      tokens: 1_200_000,
    },
    duration: { p95_ms: 4200, p50_ms: 1100, baseline_p95_ms: 4500, delta_pct: -6.6, turns: 1232, unterminated: 2 },
  }),
  groups: [
    group("answer", 12.345, {
      cost: { delta_pct_per_turn: 40, flagged: true, usd_per_turn: 0.0103 },
      duration: { p95_ms: 900, turns: 1200 },
    }),
    group("route/v2", 300, { cost: { usd_per_turn: 10 }, duration: { p95_ms: 7000, flagged: true, turns: 30 } }),
    group(null, 0.5, { label: null, duration: { turns: 2 } }),
  ],
};

const BY_MODEL: VitalsData = {
  ...DATA,
  dimension: "model",
  groups: [
    group("claude-haiku", 100, { cost: { calls: 9000, tokens: 2_500_000 } }),
    group("claude-sonnet", 400, { cost: { calls: 1200, tokens: 800_000 } }),
  ],
};

beforeEach(() => {
  api.getVitals.mockImplementation((_days: number, by: string) => Promise.resolve(by === "model" ? BY_MODEL : DATA));
});

const bodyRows = () => screen.getAllByRole("row").slice(1);
const cells = () => bodyRows().map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
const column = (i: number) => cells().map((r) => r[i]);
const sortBy = (label: string) => fireEvent.click(screen.getByRole("button", { name: new RegExp(`^${label}`) }));

describe("the totals", () => {
  it("rolls up the last 7 days with no comparison against an earlier window", async () => {
    renderRoute(<Vitals />);

    const totals = await screen.findByRole("region", { name: "Totals" });
    expect(api.getVitals).toHaveBeenCalledWith(7, "call_site");
    expect(within(totals).getByText("$512")).toBeTruthy();
    expect(within(totals).getByText("$0.043 per trace")).toBeTruthy();
    expect(within(totals).getByText("1,232")).toBeTruthy();
    expect(within(totals).getByText("2 did not finish")).toBeTruthy();
    expect(within(totals).getByText("4.2s")).toBeTruthy();
    expect(within(totals).getByText("median 1.1s")).toBeTruthy();
    expect(within(totals).getByText("1.2M")).toBeTruthy();
    expect(within(totals).getByText("across 3,400 LLM spans")).toBeTruthy();

    expect(screen.queryByText(/\+28%|−7%|\+40%|prior/)).toBeNull();
  });

  it("reads the 30-day window when asked", async () => {
    renderRoute(<Vitals />);
    await screen.findByText("Last 7 days");

    fireEvent.click(screen.getByRole("button", { name: "30 days" }));
    expect(await screen.findByText("Last 30 days")).toBeTruthy();
    expect(api.getVitals).toHaveBeenLastCalledWith(30, "call_site");
  });

  it("leaves out a note it has no number for, and dashes a value it does not have", async () => {
    api.getVitals.mockResolvedValue({ ...DATA, total: group(null, 3), groups: [] });
    renderRoute(<Vitals />);

    expect(await screen.findByText("$3.00")).toBeTruthy();
    expect(screen.getByText("—")).toBeTruthy();
    expect(screen.queryByText(/per trace|did not finish|median/)).toBeNull();
    expect(screen.getByText(/Nothing ran in this window. Vitals counts the last 7 days/)).toBeTruthy();
    expect(screen.queryByText(/no price on file/)).toBeNull();
  });

  it("names the LLM spans that ran unpriced, and where to price them", async () => {
    renderRoute(<Vitals />);

    expect(await screen.findByText(/1,234 LLM spans ran on a model with no price on file/)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Review model settings" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/settings/models",
    );
  });

  it("shows the loading frame, then any error", async () => {
    api.getVitals.mockReturnValueOnce(pending());
    renderRoute(<Vitals />);
    expect(screen.getByRole("status", { name: "Loading vitals" })).toBeTruthy();
    cleanup();

    api.getVitals.mockRejectedValue(new Error("vitals unavailable"));
    renderRoute(<Vitals />);
    expect(await screen.findByText("vitals unavailable")).toBeTruthy();
  });
});

describe("the call-site table", () => {
  it("lists call sites by spend, biggest first, with no change column", async () => {
    renderRoute(<Vitals />);
    await screen.findByText("answer");

    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Call site",
      "Spend↓",
      "Per trace",
      "p95",
      "Traces",
    ]);
    expect(cells()).toEqual([
      ["route/v2", "$300", "$10.00", "7.0s", "30"],
      ["answer", "$12.35", "$0.010", "0.9s", "1,200"],
      ["unattributed", "$0.50", "—", "—", "2"],
    ]);
  });

  it("sorts a number biggest first then flips it, puts missing values last, and sorts call sites A to Z first", async () => {
    renderRoute(<Vitals />);
    await screen.findByText("answer");

    sortBy("Spend");
    expect(column(0)).toEqual(["unattributed", "answer", "route/v2"]);
    expect(screen.getByRole("columnheader", { name: /^Spend/ }).getAttribute("aria-sort")).toBe("ascending");

    sortBy("p95");
    expect(column(3)).toEqual(["7.0s", "0.9s", "—"]);
    sortBy("Per trace");
    expect(column(2)).toEqual(["$10.00", "$0.010", "—"]);
    sortBy("Traces");
    expect(column(4)).toEqual(["1,200", "30", "2"]);
    // The unattributed row has no name to sort by, so it leads the call sites rather than posing as a "u".
    sortBy("Call site");
    expect(column(0)).toEqual(["unattributed", "answer", "route/v2"]);
    sortBy("Call site");
    expect(column(0)).toEqual(["route/v2", "answer", "unattributed"]);
  });

  it("shows the eight biggest call sites until asked for all", async () => {
    const many = Array.from({ length: 10 }, (_, i) => group(`site-${i}`, 100 - i));
    api.getVitals.mockResolvedValue({ ...DATA, groups: many });
    renderRoute(<Vitals />);
    await screen.findByText("site-0");

    expect(column(0)).toEqual(many.slice(0, 8).map((g) => g.key));
    fireEvent.click(screen.getByRole("button", { name: "Show all 10" }));
    expect(column(0)).toEqual(many.map((g) => g.key));
    fireEvent.click(screen.getByRole("button", { name: "Show fewer" }));
    expect(bodyRows()).toHaveLength(8);
  });

  it("opens a call site's traces by click or key, and leaves the unattributed row inert", async () => {
    renderRoute(<Vitals />, { route: "/vitals" });
    await screen.findByText("answer");

    fireEvent.click(bodyRows()[2]);
    fireEvent.keyDown(bodyRows()[2], { key: "Enter" });
    expect(currentLocation()).toBe("/vitals");
    expect(bodyRows()[2].getAttribute("tabindex")).toBeNull();

    fireEvent.keyDown(screen.getByRole("row", { name: "Open traces for route/v2" }), { key: "Tab" });
    expect(currentLocation()).toBe("/vitals");
    fireEvent.keyDown(screen.getByRole("row", { name: "Open traces for route/v2" }), { key: " " });
    expect(currentLocation()).toBe("/orgs/acme/projects/default/traces?call_site=route%2Fv2");
  });
});

describe("the model table", () => {
  it("reads by model, shows LLM spans and tokens instead of latency, and opens nothing", async () => {
    renderRoute(<Vitals />, { route: "/vitals" });
    await screen.findByText("answer");

    fireEvent.click(screen.getByRole("button", { name: "By model" }));
    await screen.findByText("claude-sonnet");
    expect(api.getVitals).toHaveBeenLastCalledWith(7, "model");

    expect(screen.getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Model",
      "Spend↓",
      "LLM spans",
      "Tokens",
    ]);
    expect(cells()).toEqual([
      ["claude-sonnet", "$400", "1,200", "800K"],
      ["claude-haiku", "$100", "9,000", "2.5M"],
    ]);

    fireEvent.click(bodyRows()[0]);
    expect(currentLocation()).toBe("/vitals");
    expect(screen.queryByRole("row", { name: /Open traces/ })).toBeNull();
  });
});
