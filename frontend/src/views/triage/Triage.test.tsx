// SPDX-License-Identifier: Apache-2.0
/*
 * Triage, the front door. The bugs worth catching: a row that opens another case, a lens that shows
 * the wrong bucket or cannot be left, an empty lens that says nothing, the analysis's hedged cause
 * printed as a certainty, and a pulse strip that disagrees with Vitals about spend or latency.
 */
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Case, TriageView, Vitals } from "../../api/types";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { GROUNDEDNESS_CASE_DETAIL } from "../../test/groundednessFixtures";
import { Triage } from "./Triage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getTriage: vi.fn(),
  getVitals: vi.fn(),
  getModelSettings: vi.fn(),
  onboarding: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({
  useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
  useProjectApi: () => api,
}));
vi.mock("../../capabilities/useCapabilities", () => ({ useCapabilities: () => ({ isEnabled: () => true }) }));

const kase = (id: string, over: Partial<Case> = {}): Case => ({
  ...GROUNDEDNESS_CASE_DETAIL.case,
  id,
  reference: `CASE-${id}`,
  title: `Case ${id}`,
  opened_at: new Date(Date.now() - 5 * 60_000).toISOString(),
  ...over,
});
const WATCHING = { traces_total: 100, open_findings: 0, classifiers: 3, call_sites: 2 } as TriageView["watching"];
const triage = (over: Partial<TriageView> = {}): TriageView => ({
  cases: [
    kase("a", { rca_verdict: "inconclusive", cause: "a prompt change" }),
    kase("b", { rca_verdict: null, call_site_id: null }),
  ],
  muted: [kase("m", { state: "muted", title: "A muted case" })],
  recently_resolved: [
    kase("r", { state: "resolved", title: "A resolved case", resolved_at: new Date(Date.now() - 2 * 60_000).toISOString() }),
  ],
  watching: WATCHING,
  ...over,
});
const vitals = (usd: number, p95: number | null, spendDelta: number | null, p95Delta: number | null) =>
  ({
    total: {
      cost: { usd, delta_pct_per_turn: spendDelta },
      duration: { p95_ms: p95, delta_pct: p95Delta },
    },
  }) as unknown as Vitals;

beforeEach(() => {
  api.getTriage.mockResolvedValue(triage());
  api.getVitals.mockResolvedValue(vitals(512.4, 4200, 28.4, -6.6));
  api.getModelSettings.mockResolvedValue({ configured_providers: ["anthropic"] });
  api.onboarding.mockResolvedValue({ stage: "case" });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const renderPage = () => renderRoute(<Triage />, { route: "/orgs/acme/projects/default/triage" });
const lens = (name: RegExp) => screen.getByRole("button", { name }) as HTMLButtonElement;

describe("the open cases", () => {
  it("lists each case with its hedged cause and where it happened, and opens the one pressed", async () => {
    renderPage();

    const a = (await screen.findByText("Case a")).closest("button")!;
    expect(a.textContent).toContain("Likely: a prompt change");
    expect(a.textContent).toContain("CASE-a");
    expect(a.textContent).toContain("support-agent");
    expect(a.textContent).toContain("5m ago");
    const b = screen.getByText("Case b").closest("button")!;
    expect(b.textContent).not.toContain("support-agent");
    // Reference, classifier, and age: no separator left dangling for the call site it does not have.
    expect(b.textContent!.match(/·/g)).toHaveLength(2);
    expect(a.textContent!.match(/·/g)).toHaveLength(3);
    expect(screen.getByText(/Resolved 7d · 1 · Muted · 1/)).toBeTruthy();

    fireEvent.click(b);
    expect(currentLocation()).toBe("/orgs/acme/projects/default/cases/b");
  });

  it("says why the queue could not be read", async () => {
    api.getTriage.mockRejectedValue(new Error("triage unavailable"));
    renderPage();

    expect(await screen.findByText("triage unavailable")).toBeTruthy();
  });

  it("hands an all-clear queue to the empty-state screen rather than an empty list", async () => {
    api.getTriage.mockResolvedValue(triage({ cases: [] }));
    renderPage();

    expect(await screen.findByText(/Nothing to review|Baselines are still fitting|No classifiers running/)).toBeTruthy();
    expect(screen.queryByText("Nothing needs you.")).toBeNull();
    expect(screen.queryByText(/Resolved 7d · 1 · Muted/)).toBeNull();
  });
});

describe("the lenses", () => {
  it("switches to the muted and resolved buckets and back, each pressed while it shows", async () => {
    renderPage();
    await screen.findByText("Case a");

    fireEvent.click(lens(/Muted · 1/));
    expect(screen.getByText("A muted case")).toBeTruthy();
    expect(screen.queryByText("Case a")).toBeNull();
    expect(lens(/Muted · 1/).getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(lens(/Resolved 7d · 1/));
    const resolved = screen.getByText("A resolved case").closest("button")!;
    expect(resolved.textContent).toContain("resolved 2m ago");
    expect(lens(/Muted · 1/).getAttribute("aria-pressed")).toBe("false");

    fireEvent.click(lens(/Resolved 7d · 1/));
    expect(screen.getByText("Case a")).toBeTruthy();
    fireEvent.click(lens(/Muted · 1/));
    fireEvent.click(lens(/Muted · 1/));
    expect(screen.getByText("Case a")).toBeTruthy();
  });

  it("says when a lens has nothing in it", async () => {
    api.getTriage.mockResolvedValue(triage({ muted: [], recently_resolved: [] }));
    renderPage();
    await screen.findByText("Case a");

    fireEvent.click(lens(/Muted · 0/));
    expect(screen.getByText("Nothing is muted.")).toBeTruthy();
    fireEvent.click(lens(/Resolved 7d · 0/));
    expect(screen.getByText("No cases resolved in the last 7 days.")).toBeTruthy();
  });

  it("dates a resolved case from when it opened if it carries no resolution time", async () => {
    api.getTriage.mockResolvedValue(
      triage({ recently_resolved: [kase("r", { state: "resolved", title: "A resolved case", resolved_at: null })] }),
    );
    renderPage();
    await screen.findByText("Case a");

    fireEvent.click(lens(/Resolved 7d · 1/));
    expect(screen.getByText("A resolved case").closest("button")!.textContent).toContain("resolved 5m ago");
  });
});

describe("the pulse strip", () => {
  it("prints spend and latency with their deltas, as Vitals reads them", async () => {
    renderPage();

    expect(await screen.findByText("$512")).toBeTruthy();
    expect(screen.getByText("+28%")).toBeTruthy();
    expect(screen.getByText("4.2s")).toBeTruthy();
    expect(screen.getByText("-7%")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open Vitals" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/vitals",
    );
    expect(api.getVitals).toHaveBeenCalledWith(7);
  });

  it("prints a fast p95 in milliseconds and dashes what it has not measured", async () => {
    api.getVitals.mockResolvedValueOnce(vitals(3, 850, null, 0));
    renderPage();
    expect(await screen.findByText("850ms")).toBeTruthy();
    expect(screen.getByText("$3.00")).toBeTruthy();
    expect(screen.getByText("—")).toBeTruthy();
    expect(screen.getByText("0%")).toBeTruthy();
    cleanup();

    api.getVitals.mockResolvedValueOnce(vitals(3, null, null, null));
    renderPage();
    await screen.findByText("$3.00");
    expect(screen.getAllByText("—")).toHaveLength(3);
  });

  it("draws no strip until the vitals arrive", async () => {
    api.getVitals.mockReturnValue(pending());
    renderPage();
    await screen.findByText("Case a");

    expect(screen.queryByText("Vitals 7d")).toBeNull();
  });
});
