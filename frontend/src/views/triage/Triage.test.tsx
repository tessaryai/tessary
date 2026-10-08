// SPDX-License-Identifier: Apache-2.0
/*
 * Triage, the front door. The bugs worth catching: a row that opens another case, a lens that shows
 * the wrong bucket or cannot be left, an empty lens that says nothing, the analysis's summary
 * printed as a certainty, a pulse strip that disagrees with Vitals about spend or latency, and a
 * findings list that shows a finding already in Cases, drops one that is not, or opens the wrong page.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { BehaviorFinding, Case, TriageView, Vitals } from "../../api/types";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { GROUNDEDNESS_CASE_DETAIL } from "../../test/groundednessFixtures";
import { Triage } from "./Triage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getTriage: vi.fn(),
  getVitals: vi.fn(),
  getModelSettings: vi.fn(),
  onboarding: vi.fn(),
  listBehaviorFindings: vi.fn(),
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
  api.listBehaviorFindings.mockResolvedValue({ findings: [] });
});

const renderPage = () => renderRoute(<Triage />, { route: "/orgs/acme/projects/default/triage" });
const lens = (name: RegExp) => screen.getByRole("button", { name }) as HTMLButtonElement;
const section = (title: string) => screen.getByRole("heading", { level: 2, name: title }).closest("section")!;

describe("the page", () => {
  // Bug: the page still titled "Cases", or the lens buttons left in the page header where they read as
  // filters for the findings too.
  it("is titled Triage, with the case lenses in the Cases section and Findings below it", async () => {
    renderPage();

    await screen.findByText("Case a");
    expect(screen.getByRole("heading", { level: 1, name: "Triage" })).toBeTruthy();
    const cases = section("Cases");
    expect(within(cases).getByRole("button", { name: /Muted · 1/ })).toBeTruthy();
    expect(within(cases).getByRole("button", { name: /Resolved 7d · 1/ })).toBeTruthy();
    expect(within(section("Findings")).queryByRole("button", { name: /Muted/ })).toBeNull();
    expect(cases.compareDocumentPosition(section("Findings")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe("the open cases", () => {
  /** Catches a "Likely:" prefix on a summary that already says whether anything was proven. */
  it("prints the summary as the analysis wrote it, proven or not", async () => {
    api.getTriage.mockResolvedValue(
      triage({
        cases: [
          kase("p", { rca_verdict: "behavior_change", cause: "The prompt dropped the sku field." }),
          kase("u", { rca_verdict: "inconclusive", cause: "No change was located." }),
        ],
      }),
    );
    renderPage();

    const p = (await screen.findByText("Case p")).closest("button")!;
    const u = screen.getByText("Case u").closest("button")!;
    expect(p.textContent).toContain("The prompt dropped the sku field.");
    expect(u.textContent).toContain("No change was located.");
    expect(p.textContent).not.toContain("Likely:");
    expect(u.textContent).not.toContain("Likely:");
  });

  it("lists each case with its summary and where it happened, and opens the one pressed", async () => {
    renderPage();

    const a = (await screen.findByText("Case a")).closest("button")!;
    expect(a.textContent).toContain("a prompt change");
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

  // Bug: a watched project with nothing open gets the full-page setup screen ("Nothing to review") in place of
  // the Cases list, pushing the findings it does have out of sight.
  it("says nothing needs you when no case is open and every stage is running", async () => {
    api.getTriage.mockResolvedValue(triage({ cases: [] }));
    renderPage();

    expect(await within(section("Cases")).findByText("Nothing needs you.")).toBeTruthy();
    expect(screen.queryByText("Nothing to review")).toBeNull();
  });

  // Bug: the setup screen dropped with the empty list, so a project with no classifier running reads as all
  // clear and is never told what to do next.
  it("keeps the setup screen in the Cases section while a stage still needs action", async () => {
    api.getTriage.mockResolvedValue(
      triage({ cases: [], watching: { ...WATCHING, classifiers: 0 } as TriageView["watching"] }),
    );
    renderPage();

    expect(await within(section("Cases")).findByText("No classifiers running")).toBeTruthy();
    expect(screen.queryByText("Nothing needs you.")).toBeNull();
    expect(await within(section("Findings")).findByText("No open findings.")).toBeTruthy();
  });

  // Bug: a stopped exporter hidden behind "Nothing needs you.", which reads as all clear while nothing arrives.
  it("keeps the setup screen when traces have stopped, even with every stage running", async () => {
    api.getTriage.mockResolvedValue(
      triage({ cases: [], watching: { ...WATCHING, traces_last_day: 0 } as TriageView["watching"] }),
    );
    renderPage();

    expect(await within(section("Cases")).findByText(/No traces have arrived in the last 24 hours/)).toBeTruthy();
    expect(screen.queryByText("Nothing needs you.")).toBeNull();
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

const finding = (id: string, over: Partial<BehaviorFinding> = {}) =>
  ({
    id,
    title: `Finding ${id}`,
    detector: "cost_drift",
    callSiteId: "support-agent",
    firstSeenAt: new Date(Date.now() - 3 * 60_000).toISOString(),
    triageStatus: "pending",
    triageAction: null,
    triageVerdict: null,
    humanVerdictAt: null,
    caseId: null,
    ...over,
  }) as BehaviorFinding;

describe("the findings", () => {
  // Bug: a closed finding, or one that already became a case, listed again under Findings; or a sound finding
  // still waiting for its case dropped from both lists.
  it("lists the open findings that are not yet a case, with their classifier, call site and triage", async () => {
    api.listBehaviorFindings.mockResolvedValue({
      findings: [
        finding("a"),
        finding("b", { triageStatus: "done", triageVerdict: "negative", triageAction: "closed" }),
        finding("c", { detector: "tool_error", callSiteId: "__unattributed__", triageStatus: "failed" }),
        finding("d", { triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case", caseId: "case-d" }),
        finding("e", { triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case", caseId: null }),
        finding("f", { triageStatus: "in_flight", callSiteId: null }),
      ],
    });
    renderPage();

    const findings = section("Findings");
    const a = (await within(findings).findByText("Finding a")).closest("tr")!;
    expect(within(findings).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Finding",
      "Classifier",
      "Call site",
      "Triage",
      "First seen",
      "",
    ]);
    expect(within(a).getAllByRole("cell").map((c) => c.textContent).slice(0, 5)).toEqual([
      "Finding a",
      "Cost drift",
      "support-agent",
      "Pending",
      "3m ago",
    ]);
    expect(within(findings).getByText("4")).toBeTruthy();
    expect(within(findings).queryByText("Finding b")).toBeNull();
    expect(within(findings).queryByText("Finding d")).toBeNull();
    expect(within(findings).getByText("Finding e").closest("tr")!.textContent).toContain("Positive");
    expect(within(findings).getByText("Finding f").closest("tr")!.textContent).toContain("Triaging");
    // The tool-error sentinel is not a call site, and red is too faint at table size: grey words beside a red icon.
    const c = within(findings).getByText("Finding c").closest("tr")!;
    expect(within(c).getAllByRole("cell")[2].textContent).toBe("–");
    expect(within(c).getByText("Triage failed").className).not.toContain("text-error");
    expect(api.listBehaviorFindings).toHaveBeenCalledWith();
  });

  // Bug: a finding row that opens the case list, or a path that breaks on an id with a slash in it.
  it("opens a finding's own page from its row", async () => {
    api.listBehaviorFindings.mockResolvedValue({ findings: [finding("f/1")] });
    renderPage();

    fireEvent.click(await screen.findByText("Finding f/1"));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/classifiers/findings/f%2F1");
  });

  it("says when no finding is open", async () => {
    api.listBehaviorFindings.mockResolvedValue({
      findings: [finding("b", { triageStatus: "done", triageVerdict: "negative", triageAction: "closed" })],
    });
    renderPage();

    expect(await within(section("Findings")).findByText("No open findings.")).toBeTruthy();
    expect(screen.queryByText("Finding b")).toBeNull();
  });

  it("says why the findings could not be read", async () => {
    api.listBehaviorFindings.mockRejectedValue(new Error("findings unavailable"));
    renderPage();

    expect(await within(section("Findings")).findByText("findings unavailable")).toBeTruthy();
    expect(screen.queryByText("No open findings.")).toBeNull();
  });
});
