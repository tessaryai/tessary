// SPDX-License-Identifier: Apache-2.0
/*
 * FindingPage beyond groundedness: the shift and rate headers, a finding with no figure, triage's
 * ruling and its receipts, and the verbs. The bugs worth catching: a verb sent for another finding or
 * offered on a ruled one, a dead triage run that reads as the button doing nothing, a failing-trace
 * link that resolves somewhere else, and a ruling that hides the scripts a reader could re-run.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import type { BehaviorFindingDetail, FrustrationDetail } from "../../api/types";
import { pending } from "../../test/render";
import { ToastProvider } from "../../ui";
import { GROUNDEDNESS_DETAIL, GROUNDEDNESS_FINDING_DETAIL } from "../../test/groundednessFixtures";
import { FindingPage } from "./FindingPage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getBehaviorFinding: vi.fn(),
  getBehaviorFindingEvidence: vi.fn(),
  analyzeBehaviorFinding: vi.fn(),
  resolveBehaviorFinding: vi.fn(),
  getFrustratedSessions: vi.fn(),
  getMalformedOutputs: vi.fn(),
  getFlaggedAnswers: vi.fn(),
  getTrace: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return { ...actual, useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }) };
});

const BASE = GROUNDEDNESS_FINDING_DETAIL;
type Finding = BehaviorFindingDetail["finding"];

/** A finding whose detector draws no figure: the generic layout. */
function plain(over: Partial<Finding> = {}, detail: Partial<BehaviorFindingDetail> = {}): BehaviorFindingDetail {
  return {
    ...BASE,
    groundedness: null,
    ...detail,
    finding: { ...BASE.finding, detector: "loop", title: "The agent loops on refunds", callSiteId: "refunds", ...over },
  };
}

const RATE = { ...GROUNDEDNESS_DETAIL.rate, bucketKey: "search_tool", failingTraces: ["0123456789abcdef"] };
const SHIFT = {
  bucketKey: "cost|checkout",
  bucketKind: "call_site",
  nCur: 400,
  nRef: 1200,
  sinceVersionId: null,
  windowClosedAt: "2026-09-23T00:00:00Z",
  windowKind: null,
  windowOpenedAt: "2026-09-16T00:00:00Z",
  w1Log: 0.4,
  control: null,
  direction: "up",
  explains: [],
  floor: 0,
  measure: "cost",
  quantiles: [
    { key: "p50", then: 1, now: 2.5 },
    { key: "p95", then: 10, now: 40 },
  ],
  ratio: 2.5,
  reference: "previous",
  tokens: [],
  workload: [],
} as unknown as NonNullable<BehaviorFindingDetail["metric"]>;

beforeEach(() => {
  api.getBehaviorFinding.mockResolvedValue(plain());
  api.getBehaviorFindingEvidence.mockResolvedValue({ rows: [], nextCursor: null, counts: {}, recordedCounts: {} });
  api.analyzeBehaviorFinding.mockResolvedValue({ jobStatus: "queued" });
  api.resolveBehaviorFinding.mockResolvedValue({});
  api.getFrustratedSessions.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getTrace.mockReturnValue(pending());
  api.getMalformedOutputs.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getFlaggedAnswers.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** Nested as the app nests it, so `..` and `../traces/…` resolve against the project route. */
const renderPage = () =>
  render(
    <MemoryRouter initialEntries={["/orgs/acme/projects/default/classifiers/findings/fnd-1"]}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <ToastProvider>
          <Routes>
            <Route path="/orgs/:orgSlug/projects/:projectSlug">
              <Route index element={<Probe />} />
              <Route path="classifiers/findings/:findingId" element={<FindingPage />} />
            </Route>
          </Routes>
        </ToastProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );

function Probe() {
  return <output aria-label="location">{useLocation().pathname}</output>;
}
const currentLocation = () => screen.getByLabelText("location").textContent;
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;

describe("a finding with no figure", () => {
  it("names itself, says there is nothing to plot, and runs triage on this finding", async () => {
    api.getBehaviorFinding.mockResolvedValueOnce(plain()).mockResolvedValue(plain({ triageStatus: "in_flight" }));
    renderPage();

    await screen.findByRole("heading", { name: "The agent loops on refunds" });
    expect(screen.getByText("seen 58× · not triaged yet")).toBeTruthy();
    expect(screen.getByText("refunds")).toBeTruthy();
    expect(screen.getByText(/This cause carries no measured shift/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Evidence" })).toBeTruthy();

    fireEvent.click(button("Run triage"));

    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
    expect(await screen.findByRole("button", { name: "Triaging…" })).toBeTruthy();
  });

  it("says when triage gave up and is cooling down, and names a refused run", async () => {
    api.analyzeBehaviorFinding.mockResolvedValueOnce({ jobStatus: "dead" }).mockRejectedValueOnce(new Error("busy"));
    renderPage();
    await screen.findByRole("heading", { name: "The agent loops on refunds" });

    fireEvent.click(button("Run triage"));
    expect(await screen.findByText(/Triage gave up on this finding recently/)).toBeTruthy();
    fireEvent.click(button("Run triage"));
    expect(await screen.findByText("busy")).toBeTruthy();
  });

  it("records a person's verdict on this finding and returns to where it was opened from", async () => {
    api.resolveBehaviorFinding.mockRejectedValueOnce(new Error("already ruled"));
    renderPage();
    await screen.findByRole("heading", { name: "The agent loops on refunds" });

    fireEvent.click(button("Absorb as legitimate"));
    expect(await screen.findByText("already ruled")).toBeTruthy();
    fireEvent.click(button("Confirm and open a case"));

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default"));
    expect(api.resolveBehaviorFinding.mock.calls).toEqual([
      ["fnd-1", "expected"],
      ["fnd-1", "not_expected"],
    ]);
  });

  it("shows triage's ruling with what it read and the scripts it ran, and offers no verbs", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain({
        triageStatus: "done",
        triageAction: "closed",
        triageVerdict: "positive",
        triageSummary: "Refund retries doubled after the prompt change.",
        triagedAt: "2026-09-23T15:00:00Z",
        caseId: "case-7",
        triageCitations: [
          { path: "window.n_cur", reason: "the current window's size", stdout: null },
          { path: "trace/abc", reason: null, stdout: null },
          { path: "checks/retries.py", reason: "counts retries per session", stdout: "before 1.1\nafter 2.3" },
          { path: "checks/empty.py", reason: null, stdout: "" },
        ] as Finding["triageCitations"],
      }),
    );
    renderPage();

    expect(await screen.findByText("Positive: the claim holds, and a case is open on it.")).toBeTruthy();
    expect(screen.getByText("Refund retries doubled after the prompt change.")).toBeTruthy();
    expect(screen.getAllByRole("link", { name: "Opened case →" })[0].getAttribute("href")).toBe(
      "/orgs/acme/projects/default/cases/case-7",
    );
    expect(screen.getByText("window.n_cur").parentElement!.textContent).toBe("window.n_cur: the current window's size");
    expect(screen.getByText("trace/abc").parentElement!.textContent).toBe("trace/abc");
    const script = screen.getByText("checks/retries.py").parentElement!;
    expect(within(script).getByText("counts retries per session")).toBeTruthy();
    expect(within(script).getByText(/before 1.1/).tagName).toBe("PRE");
    expect(screen.getByText("checks/empty.py").parentElement!.querySelector("pre")).toBeNull();
    expect(screen.queryByRole("button", { name: /Run triage|Absorb|Confirm/ })).toBeNull();
    expect(screen.getByText("Positive")).toBeTruthy();
  });

  it("says what a negative ruling means, and shows no receipts it does not have", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus: "done", triageVerdict: "negative", triageCitations: [] }));
    renderPage();

    expect(await screen.findByText("Negative: the measurement is wrong, so there is nothing to explain.")).toBeTruthy();
    expect(screen.queryByText("What it read")).toBeNull();
    expect(screen.queryByText("What it computed")).toBeNull();
    // Ruled but not closed by triage, so no "closed" pill beside the chain.
    expect(screen.queryByText("Closed · negative")).toBeNull();
  });

  it("names a finding without a detector plainly, and shows a failed read", async () => {
    api.getBehaviorFinding.mockResolvedValueOnce(plain({ detector: "" }));
    renderPage();
    await screen.findByRole("heading", { name: "The agent loops on refunds" });
    // The breadcrumb's "Finding", and the kicker's in place of a detector name.
    expect(screen.getAllByText("Finding")).toHaveLength(2);
    cleanup();

    api.getBehaviorFinding.mockRejectedValue(new Error("finding unavailable"));
    renderPage();
    expect(await screen.findByText("finding unavailable")).toBeTruthy();
  });
});

describe("a shift finding", () => {
  it("heads the page with the median's move, the bucket and window it moved in, and one triage button", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ detector: "cost_drift" }, { metric: SHIFT }));
    renderPage();

    const title = await screen.findByRole("heading", { level: 1 });
    expect(title.textContent).toMatch(/\$1\.00 → \$2\.50$/);
    expect(screen.getByTitle("groundedness_rate:support-agent").textContent).toMatch(/^cost\|checkout · .+ vs /);
    expect(screen.getByRole("heading", { name: "What moved" })).toBeTruthy();

    fireEvent.click(button("Run triage"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  it("shows the ruling in place of the button once triaged, and drops a bucket that is the call site", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "cost_drift", callSiteId: "cost|checkout", triageStatus: "done", triageVerdict: "negative" },
        { metric: { ...SHIFT, windowOpenedAt: null } },
      ),
    );
    renderPage();

    expect(await screen.findByText("Closed · negative")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Run triage" })).toBeNull();
    expect(screen.getByTitle("groundedness_rate:support-agent").textContent).toBe("");
  });
});

describe("a rate finding", () => {
  it("heads the page with the failure rate's move, and links each failing trace into this project", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ detector: "tool_error" }, { toolError: RATE }));
    renderPage();

    const title = await screen.findByRole("heading", { level: 1 });
    expect(title.textContent).toMatch(/^Failure rate/);
    expect(screen.getByTitle("groundedness_rate:support-agent").textContent).toMatch(
      /^search_tool · since .+ vs the rate it was fitted at$/,
    );
    expect(screen.getByRole("link", { name: "0123456789…" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/0123456789abcdef",
    );

    fireEvent.click(button("Run triage"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  it("shows the ruling once triaged, and leaves out an onset it does not have", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "tool_error", triageStatus: "done", triageVerdict: "positive", triageSummary: "The tool's API changed." },
        { toolError: { ...RATE, onsetAt: null, failingTraces: [] } },
      ),
    );
    renderPage();

    expect(await screen.findByText("The tool's API changed.")).toBeTruthy();
    expect(screen.getByTitle("groundedness_rate:support-agent").textContent).toBe("search_tool");
    expect(screen.queryByText("Failing traces")).toBeNull();
  });
});

describe("the triage verbs in each story's header", () => {
  const T0 = "2026-01-06T10:00:00Z";
  it.each([
    [
      "secret leak",
      {
        secretLeak: {
          basis: "Any high-confidence match opens a case.",
          confidence: "high",
          firstAt: T0,
          lastAt: T0,
          keys: [{ lastAt: T0, leaks: 1, masked: "sk-a…9f2c", storedRaw: true, traces: 1 }],
          leakCount: 1,
          leaks: [{ at: T0, masked: "sk-a…9f2c", spanId: "span-1", stored: "raw", traceId: "trace-1" }],
          rule: "openai_api_key",
          threshold: 1,
          traceCount: 1,
          windowEnd: T0,
          windowSeconds: 86400,
          windowStart: T0,
        },
      },
    ],
    ["malformed output", { malformedOutput: { fields: [], notJson: 0, other: 0, rate: RATE } }],
    ["groundedness", { groundedness: GROUNDEDNESS_DETAIL }],
  ])("runs triage on this %s finding", async (_kind, detail) => {
    api.getBehaviorFinding.mockResolvedValue(plain({}, detail as Partial<BehaviorFindingDetail>));
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Run triage" }));

    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  it("records a person's verdict from the groundedness header", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({}, { groundedness: GROUNDEDNESS_DETAIL }));
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Absorb as legitimate" }));

    await waitFor(() => expect(api.resolveBehaviorFinding).toHaveBeenCalledWith("fnd-1", "expected"));
  });
});

describe("a frustration finding", () => {
  it("says its sessions were cleared when the case was resolved as a false alarm", async () => {
    const cleared = { traceId: "t-1", conversationId: "c-1", message: "ugh", cleared: true, contextTraceIds: [] };
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "frustration" },
        {
          frustration: {
            rate: RATE,
            arlTarget: 50000,
            baselineFrustrated: 10,
            jevThreshold: 0.5,
            minDecisionInterval: 3600,
            scorerVersion: "v1",
            conversations: [cleared],
            conversationsNextCursor: null,
          } as unknown as FrustrationDetail,
        },
      ),
    );
    renderPage();

    expect(await screen.findByText(/Cleared when the case was resolved as a false alarm/)).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Evidence" })).toBeNull();
  });

  it("does not call an empty session list cleared", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "frustration" },
        {
          frustration: {
            rate: RATE,
            arlTarget: 50000,
            baselineFrustrated: 10,
            jevThreshold: 0.5,
            minDecisionInterval: 3600,
            scorerVersion: "v1",
            conversations: [],
            conversationsNextCursor: null,
          } as unknown as FrustrationDetail,
        },
      ),
    );
    renderPage();

    expect(await screen.findByText("Each flagged message with the turns before it")).toBeTruthy();
  });
});
