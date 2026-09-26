// SPDX-License-Identifier: Apache-2.0
/*
 * Vitals. The page never judges: the amber tint comes from the server's own `flagged`, so the bugs worth
 * catching are a tint the server did not ask for, a sort that orders strings as numbers or puts a missing
 * value first, a row that opens the wrong call site's traces (or any traces for the unattributed row),
 * and a spend or latency printed wrong.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

const DATA: VitalsData = {
  dimension: "call_site",
  priced_models: 3,
  window: { days: 7, from: "", to: "", baseline_from: "", baseline_to: "" },
  total: group(null, 512.4, {
    cost: { baseline_usd: 400, delta_pct_per_turn: 28.4, flagged: true, unpriced_calls: 1234 },
    duration: { p95_ms: 4200, baseline_p95_ms: 4500, delta_pct: -6.6 },
  }),
  groups: [
    group("answer", 12.345, { cost: { delta_pct_per_turn: 40, flagged: true }, duration: { p95_ms: 900, turns: 1200 } }),
    group("route/v2", 300, { cost: { delta_pct_per_turn: -3.4 }, duration: { p95_ms: 7000, flagged: true, turns: 30 } }),
    group(null, 0.5, { label: null, duration: { turns: 2 } }),
  ],
};

beforeEach(() => {
  api.getVitals.mockResolvedValue(DATA);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const bodyRows = () => screen.getAllByRole("row").slice(1);
const column = (i: number) => bodyRows().map((r) => within(r).getAllByRole("cell")[i].textContent);
const sortBy = (label: string) => fireEvent.click(screen.getByRole("button", { name: new RegExp(`^${label}`) }));

describe("the headline", () => {
  it("reads the last 7 days against the prior 7, in dollars and seconds, tinted only where flagged", async () => {
    renderRoute(<Vitals />);

    const spend = (await screen.findByText("$512")).parentElement!.parentElement!;
    expect(api.getVitals).toHaveBeenCalledWith(7, "call_site");
    expect(within(spend).getByText("+28%").className).toContain("text-warning");
    expect(within(spend).getByText("prior 7d $400")).toBeTruthy();
    const latency = screen.getByText("4.2s").parentElement!.parentElement!;
    expect(within(latency).getByText("−7%").className).toContain("text-muted");
    expect(within(latency).getByText("prior 7d 4.5s")).toBeTruthy();
  });

  it("says when there is no prior window, and dashes a number it does not have", async () => {
    api.getVitals.mockResolvedValue({ ...DATA, total: group(null, 3), groups: [] });
    renderRoute(<Vitals />);

    expect(await screen.findByText("$3.00")).toBeTruthy();
    expect(screen.getAllByText("no prior window")).toHaveLength(2);
    expect(screen.getAllByText("—")).toHaveLength(3);
    expect(screen.getByText(/Nothing ran in this window. Vitals counts the last 7 days/)).toBeTruthy();
    expect(screen.queryByText(/no price on file/)).toBeNull();
  });

  it("names the calls that ran unpriced, and where to price them", async () => {
    renderRoute(<Vitals />);

    expect(await screen.findByText(/1,234 calls ran on a model with no price on file/)).toBeTruthy();
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
  it("prints each row as the server sent it, tinting only the flagged metric", async () => {
    renderRoute(<Vitals />);
    await screen.findByText("answer");

    expect(bodyRows().map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))).toEqual([
      ["answer", "$12.35", "+40%", "0.9s", "1,200"],
      ["route/v2", "$300", "−3%", "7.0s", "30"],
      ["unattributed", "$0.50", "—", "—", "2"],
    ]);
    const tinted = (r: HTMLElement) =>
      within(r).getAllByRole("cell").map((c) => c.className.includes("text-warning"));
    expect(tinted(bodyRows()[0])).toEqual([false, true, true, false, false]);
    expect(tinted(bodyRows()[1])).toEqual([false, false, false, true, false]);
  });

  it("sorts a number biggest first then flips it, puts missing values last, and sorts call sites A to Z first", async () => {
    renderRoute(<Vitals />);
    await screen.findByText("answer");

    sortBy("Spend");
    expect(column(0)).toEqual(["route/v2", "answer", "unattributed"]);
    expect(screen.getByRole("columnheader", { name: /Spend/ }).getAttribute("aria-sort")).toBe("descending");
    sortBy("Spend");
    expect(column(0)).toEqual(["unattributed", "answer", "route/v2"]);
    expect(screen.getByRole("columnheader", { name: /^Spend/ }).getAttribute("aria-sort")).toBe("ascending");

    sortBy("p95");
    expect(column(3)).toEqual(["7.0s", "0.9s", "—"]);
    sortBy("Δ spend / turn");
    expect(column(2)).toEqual(["+40%", "−3%", "—"]);
    sortBy("Turns");
    expect(column(4)).toEqual(["1,200", "30", "2"]);
    // The unattributed row has no name to sort by, so it leads the call sites rather than posing as a "u".
    sortBy("Call site");
    expect(column(0)).toEqual(["unattributed", "answer", "route/v2"]);
    sortBy("Call site");
    expect(column(0)).toEqual(["route/v2", "answer", "unattributed"]);
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
