// SPDX-License-Identifier: Apache-2.0
/*
 * FindingPage: groundedness's rate and flagged answers, the shift and rate headers, a finding with no
 * figure, triage's ruling and its receipts, and the verbs. The bugs worth catching: a verb sent for
 * another finding or offered on a ruled one, a dead triage run that reads as the button doing nothing,
 * a failing-trace link that resolves somewhere else, and a ruling that hides the scripts a reader
 * could re-run.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { BehaviorFindingDetail, ClassifierDetail, FrustrationDetail } from "../../api/types";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { GROUNDEDNESS_DETAIL, GROUNDEDNESS_FINDING_DETAIL } from "../../test/groundednessFixtures";
import { FindingPage } from "./FindingPage";
import { dateTime } from "./groundedness";
import { TRIAGE_POLL_MS, stamp } from "./shared";
import { api, auth } from "../../test/api";
import { stepsPage, TRIAGE_STEPS } from "../../test/runFixtures";

vi.mock("../../api/client", () => import("../../test/api"));
vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const BASE = GROUNDEDNESS_FINDING_DETAIL;
type Finding = BehaviorFindingDetail["finding"];

/** A finding whose detector draws no figure: the generic layout. */
function plain(over: Partial<Finding> = {}, detail: Partial<BehaviorFindingDetail> = {}): BehaviorFindingDetail {
  return {
    ...BASE, groundedness: null, ...detail,
    finding: { ...BASE.finding, detector: "loop", title: "The agent loops on refunds", callSiteId: "refunds", ...over },
  };
}

const RATE = { ...GROUNDEDNESS_DETAIL.rate, bucketKey: "search_tool", failingTraces: ["0123456789abcdef"] };
const SHIFT = {
  bucketKey: "cost|checkout", bucketKind: "call_site", nCur: 400, nRef: 1200, sinceVersionId: null,
  windowClosedAt: "2026-09-23T00:00:00Z", windowKind: null, windowOpenedAt: "2026-09-16T00:00:00Z", w1Log: 0.4,
  control: null, direction: "up", explains: [], floor: 0, measure: "cost",
  quantiles: [{ key: "p50", then: 1, now: 2.5 }, { key: "p95", then: 10, now: 40 }],
  ratio: 2.5, reference: "previous", tokens: [], workload: [],
} as unknown as NonNullable<BehaviorFindingDetail["metric"]>;

beforeEach(() => {
  auth.getCapabilities.mockResolvedValue({ capabilities: { byo_provider_keys_enabled: true } });
  api.getBehaviorFinding.mockResolvedValue(plain());
  api.getBehaviorFindingEvidence.mockResolvedValue({ rows: [], nextCursor: null, counts: {}, recordedCounts: {} });
  api.analyzeBehaviorFinding.mockResolvedValue({ jobStatus: "queued" });
  api.resolveBehaviorFinding.mockResolvedValue({});
  api.getFrustratedSessions.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getTrace.mockReturnValue(pending());
  api.getMalformedOutputs.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getFlaggedAnswers.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
});

/** Nested as the app nests it, so `..` and `../traces/…` resolve against the project route. */
const renderPage = () =>
  renderRoute(<FindingPage />, {
    route: "/orgs/acme/projects/default/classifiers/findings/fnd-1",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers/findings/:findingId",
  });
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;

describe("a finding with no figure", () => {
  // Bug: the breadcrumb leads to Classifiers, which charts and lists no findings, not back to Triage, which does.
  // Bug: a story header keeps its own breadcrumb, so it can still lead to Classifiers after the generic one moved.
  it.each([
    ["its", plain(), "The agent loops on refunds"],
    ["the groundedness story's", GROUNDEDNESS_FINDING_DETAIL, "Answers on support-agent became less grounded"],
  ])("leads back to Triage from %s breadcrumb", async (_kind, finding, title) => {
    api.getBehaviorFinding.mockResolvedValue(finding);
    renderPage();
    await screen.findByText(title);

    fireEvent.click(within(screen.getByRole("navigation", { name: "Breadcrumb" })).getByRole("link", { name: "Triage" }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/triage");
  });

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

  // Bug: a press on a run still cooling down looked like the button doing nothing; the bar now says when it can run.
  it("says when triage gave up and is cooling down, and names a refused run", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({
      triageStatus: "failed",
      triageProgress: {
        attempts: 5, maxAttempts: 5, startedAt: null, heldBy: null, queuedAt: null, retryAt: null,
        revivableAt: "2026-09-23T15:30:00Z", cooldownSeconds: 1800,
      },
    }));
    api.analyzeBehaviorFinding.mockResolvedValueOnce({ jobStatus: "dead" }).mockRejectedValueOnce(new Error("busy"));
    renderPage();
    await screen.findByRole("heading", { name: "The agent loops on refunds" });

    fireEvent.click(button("Run triage again"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledTimes(1));
    expect(
      screen.getByText("Stopped after 5 attempts with no ruling. It can run again 30 minutes after it stopped."),
    ).toBeTruthy();
    fireEvent.click(button("Run triage again"));
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
    expect(api.resolveBehaviorFinding.mock.calls).toEqual([["fnd-1", "expected"], ["fnd-1", "not_expected"]]);
  });

  it("shows triage's ruling with what it read and the scripts it ran, and offers no verbs", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain({
        triageStatus: "done", triageAction: "closed", triageVerdict: "positive",
        triageSummary: "Refund retries doubled after the prompt change.", triagedAt: "2026-09-23T15:00:00Z",
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

describe("a triage run that has not ruled yet", () => {
  afterEach(() => vi.useRealTimers());

  const progress = (over: Partial<NonNullable<Finding["triageProgress"]>> = {}) => ({
    attempts: 1, maxAttempts: 5, startedAt: null, heldBy: null, queuedAt: null, retryAt: null, revivableAt: null,
    cooldownSeconds: 1800, ...over,
  });

  // Bug: the page read the finding once after Run triage, so it said "Triaging…" until someone reloaded it.
  it("keeps reading the finding while triage runs, and shows the ruling once it lands", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.getBehaviorFinding
      .mockResolvedValueOnce(plain({ triageStatus: "in_flight", triageProgress: progress() }))
      .mockResolvedValue(plain({
        triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case", triageProgress: null,
        triageSummary: "Refund retries doubled after the prompt change.", triagedAt: "2026-09-23T15:00:00Z",
      }));
    renderPage();
    expect(await screen.findByRole("button", { name: "Triaging…" })).toBeTruthy();

    await act(() => vi.advanceTimersByTimeAsync(TRIAGE_POLL_MS));

    expect(await screen.findByText("Refund retries doubled after the prompt change.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Triaging…" })).toBeNull();
  });

  // Bug: a run with nothing left to wait on kept the page reading the server for ever.
  it("stops reading the finding once the run is held for a person", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.getBehaviorFinding.mockResolvedValue(
      plain({ triageStatus: "blocked", triageProgress: progress({ heldBy: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" }) }),
    );
    renderPage();
    await screen.findByRole("button", { name: "Run triage again" });
    const reads = api.getBehaviorFinding.mock.calls.length;

    await act(() => vi.advanceTimersByTimeAsync(TRIAGE_POLL_MS * 3));

    expect(api.getBehaviorFinding.mock.calls.length).toBe(reads);
  });

  // Bug: a run held on a rejected key read as "Triaging" for ever, with nothing saying what to fix. The words moved
  // from beside the button into the bar under the header, as the design shows them.
  it.each([
    ["CLASSIFIER.TRIAGE_PROVIDER_REJECTED", "Stopped: the provider rejected the stored key. Fix the key, then run triage again.", "Open Providers"],
    ["CLASSIFIER.TRIAGE_REQUEST_REFUSED", /^Stopped: the provider refused the request\./, "Open Models"],
    ["MODEL_CONFIG.MISSING_CREDENTIALS", /^Stopped: there is no key for the provider triage runs on\./, "Open Providers"],
    ["CLASSIFIER.TRIAGE_LAUNCHER_MISCONFIGURED", /^Stopped: the sandbox launcher refused the run\./, null],
    ["CLOUD.SOMETHING_NEW", "Stopped: the run hit a setting it cannot use. Fix it, then run triage again.", null],
  ])("says what to fix when a run is held on %s, and offers to run it again", async (heldBy, words, fix) => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus: "blocked", triageProgress: progress({ heldBy }) }));
    renderPage();

    expect(await screen.findByText(words)).toBeTruthy();
    expect(screen.getByText("seen 58× · triage blocked")).toBeTruthy();
    if (fix) expect(await screen.findByRole("link", { name: fix })).toBeTruthy();
    else expect(screen.queryByRole("link", { name: /^Open / })).toBeNull();
    fireEvent.click(button("Run triage again"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  // Bug: a person who cannot open Providers was sent to a page that refuses them.
  it("offers no way to Providers to a person who cannot open it", async () => {
    auth.getCapabilities.mockResolvedValue({ capabilities: { byo_provider_keys_enabled: false } });
    api.getBehaviorFinding.mockResolvedValue(
      plain({ triageStatus: "blocked", triageProgress: progress({ heldBy: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" }) }),
    );
    renderPage();

    expect(await screen.findByText(/^Stopped: the provider rejected the stored key/)).toBeTruthy();
    await waitFor(() => expect(auth.getCapabilities).toHaveBeenCalled());
    expect(screen.queryByRole("link", { name: "Open Providers" })).toBeNull();
  });

  // Bug: a run that gave up said only that it failed, not that it had already been tried.
  it("says how many times a run that gave up was tried, and offers to run it again", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus: "failed", triageProgress: progress({ attempts: 5 }) }));
    renderPage();

    expect(
      await screen.findByText("Stopped after 5 attempts with no ruling. It can run again 30 minutes after it stopped."),
    ).toBeTruthy();
    expect(button("Run triage again").disabled).toBe(false);
  });

  // Bug: a run waiting for the worker, or for its next attempt, read the same as one running. The design names a
  // retrying run's button "Triaging…" too; the bar says that an attempt ended.
  it.each([
    ["queued", progress({ attempts: 0, queuedAt: new Date(Date.now() - 6_000).toISOString() }), "Queued…",
      "Queued. It starts when a worker is free."],
    ["retrying", progress({ attempts: 1 }), "Triaging…", "Attempt 1 of 5 ended with no ruling. Trying again…"],
    ["in_flight", progress({ attempts: 2, startedAt: new Date(Date.now() - 120_000).toISOString() }), "Triaging…",
      "Thinking…"],
  ] as const)("says where a %s run stands and keeps the button off", async (triageStatus, triageProgress, label, words) => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus, triageProgress }));
    renderPage();

    expect(await screen.findByText(words)).toBeTruthy();
    expect(button(label).disabled).toBe(true);
  });

  // Bug: the bar's clock stood still between two reads of the finding, so a queued run seemed stuck.
  it("ticks the bar's clock each second while the run waits or runs", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.getBehaviorFinding.mockResolvedValue(plain({
      triageStatus: "in_flight", triageProgress: progress({ startedAt: new Date(Date.now() - 134_000).toISOString() }),
      triageRun: RUN,
    }));
    renderPage();
    expect(await screen.findByText("step 7 · 2m 14s")).toBeTruthy();

    await act(() => vi.advanceTimersByTimeAsync(2_000));

    expect(screen.getByText("step 7 · 2m 16s")).toBeTruthy();
  });
});

const RUN: NonNullable<Finding["triageRun"]> = {
  id: "run-1", attempt: 1, startedAt: "2026-09-23T14:00:00Z", endedAt: null, stepCount: 7,
  doing: "Reading flagged answers 51 to 58", outcome: null, stopCode: null,
};
const PROGRESS: NonNullable<Finding["triageProgress"]> = {
  attempts: 1, maxAttempts: 5, startedAt: "2026-09-23T14:00:00Z", heldBy: null, queuedAt: "2026-09-23T13:59:00Z",
  retryAt: null, revivableAt: null, cooldownSeconds: 1800,
};
const RUNNING = { triageStatus: "in_flight", triageProgress: PROGRESS, triageRun: RUN } as const;

/** The bar's frame: the element that holds its mark. */
const theBar = () => screen.getByRole("img", { name: /^(Queued|Running|Stopped)$/ }).closest("div.rounded-card")!;
const before = (a: Element, b: Element) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

describe("the run bar under the header", () => {
  // Bug: the bar sat somewhere other than under the header, or the section after it ran into it with no divider.
  it.each([
    ["queued", { triageStatus: "queued", triageProgress: PROGRESS }, "Queued"],
    ["running", RUNNING, "Running"],
    ["retrying", { triageStatus: "retrying", triageProgress: PROGRESS, triageRun: { ...RUN, endedAt: "2026-09-23T14:05:00Z" } }, "Running"],
    ["blocked", { triageStatus: "blocked", triageProgress: { ...PROGRESS, heldBy: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" } }, "Stopped"],
    ["failed", { triageStatus: "failed", triageProgress: { ...PROGRESS, attempts: 5 } }, "Stopped"],
  ] as const)(
    "the bar shows under the header while triage is %s, and the first section gets its divider",
    async (_state, over, mark) => {
      api.getBehaviorFinding.mockResolvedValue({
        ...GROUNDEDNESS_FINDING_DETAIL, finding: { ...GROUNDEDNESS_FINDING_DETAIL.finding, ...over },
      });
      renderPage();

      expect(await screen.findByRole("img", { name: mark })).toBeTruthy();
      const heading = screen.getByRole("heading", { level: 1 });
      const section = screen.getByRole("heading", { name: "What changed" }).closest("section")!;
      expect(before(heading, theBar())).toBe(true);
      expect(before(theBar(), section)).toBe(true);
      expect(section.className).toContain("border-t");
      expect(section.style.marginTop).toBe("28px");
    },
  );

  // Bug: the page changed when nothing runs: a finding never sent to triage grew a bar or a divider.
  it("no bar and no divider before triage is asked for", async () => {
    api.getBehaviorFinding.mockResolvedValue(GROUNDEDNESS_FINDING_DETAIL);
    renderPage();

    const section = (await screen.findByRole("heading", { name: "What changed" })).closest("section")!;
    expect(screen.queryByRole("img", { name: /^(Queued|Running|Stopped)$/ })).toBeNull();
    expect(section.className).not.toContain("border-t");
    expect(section.style.marginTop).toBe("24px");
  });

  // Bug: a page that reads steps nobody asked for; the paid stub answers 404 to any steps read.
  it("the page reads steps only when they are opened", async () => {
    api.getTriageSteps.mockResolvedValue(stepsPage(TRIAGE_STEPS));
    api.getBehaviorFinding.mockResolvedValue({
      ...GROUNDEDNESS_FINDING_DETAIL, finding: { ...GROUNDEDNESS_FINDING_DETAIL.finding, ...RUNNING },
    });
    renderPage();
    await screen.findByText("Reading flagged answers 51 to 58…");
    expect(api.getTriageSteps).not.toHaveBeenCalled();

    fireEvent.click(button("Show steps"));

    expect(await screen.findByText("Read the finding and its numbers")).toBeTruthy();
    expect(api.getTriageSteps).toHaveBeenCalledWith("fnd-1", 0);
  });

  // Bug: once the run ruled, its steps were gone with the bar, so nobody could see how the ruling was reached.
  it.each([
    ["the groundedness story's ruling card", GROUNDEDNESS_FINDING_DETAIL],
    ["the generic layout's ruling", plain()],
  ])("the ruling keeps a fold that reopens the steps once the run ends (%s)", async (_layout, detail) => {
    api.getTriageSteps.mockResolvedValue(stepsPage(TRIAGE_STEPS.map((step) => ({ ...step, ended_ms: 180_000, ok: true }))));
    api.getBehaviorFinding.mockResolvedValue({
      ...detail,
      finding: {
        ...detail.finding, triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case",
        triageSummary: "Confirmed.", caseId: "case-1",
        triageRun: { ...RUN, stepCount: 12, endedAt: "2026-09-23T14:04:00Z", outcome: "done", doing: null },
      },
    });
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Did 12 steps in 4m" }));

    expect(await screen.findByText("Ran checks/unsupported_terms.py")).toBeTruthy();
    expect(api.getTriageSteps).toHaveBeenCalledWith("fnd-1", 0);
    expect(screen.queryByRole("img", { name: /^(Queued|Running|Stopped)$/ })).toBeNull();
  });

  // Bug: a ruling a person made, after a run that stopped, claimed the stopped run's steps as how it was reached.
  it("a ruling no run made has no fold", async () => {
    api.getBehaviorFinding.mockResolvedValue({
      ...GROUNDEDNESS_FINDING_DETAIL,
      finding: {
        ...GROUNDEDNESS_FINDING_DETAIL.finding, triageStatus: "done", triageVerdict: "positive", triageSummary: "Confirmed.",
        humanVerdictAt: "2026-09-23T14:30:00Z",
        triageRun: { ...RUN, endedAt: "2026-09-23T14:04:00Z", outcome: "held", stopCode: "CLASSIFIER.TRIAGE_PROVIDER_REJECTED" },
      },
    });
    renderPage();

    expect(await screen.findByText("Confirmed.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^Did / })).toBeNull();
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

  // Bug: the window named its days only, so a drift that ran from morning to afternoon read as a whole day
  // and did not match the case header's clock.
  it("names the window to the minute, as the case header does", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ detector: "cost_drift" }, { metric: SHIFT }));
    renderPage();
    await screen.findByRole("heading", { level: 1 });

    expect(screen.getByTitle("groundedness_rate:support-agent").textContent).toBe(
      `cost|checkout · ${stamp("2026-09-16T00:00:00Z")} → ${stamp("2026-09-23T00:00:00Z")} vs its recent normal`,
    );
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

const T0 = "2026-01-06T10:00:00Z";
const LEAK = {
  basis: "Any high-confidence match opens a case.", confidence: "high", firstAt: T0, lastAt: T0,
  keys: [{ lastAt: T0, leaks: 1, masked: "sk-a…9f2c", storedRaw: true, traces: 1 }],
  leakCount: 1,
  leaks: [{ at: T0, masked: "sk-a…9f2c", spanId: "span-1", stored: "raw", traceId: "trace-1" }],
  rule: "openai_api_key", threshold: 1, traceCount: 1, windowEnd: T0, windowSeconds: 86400, windowStart: T0,
} as unknown as NonNullable<BehaviorFindingDetail["secretLeak"]>;

describe("the triage verbs in each story's header", () => {
  it.each([
    ["secret leak", { secretLeak: LEAK }],
    ["malformed output", { malformedOutput: { fields: [], notJson: 0, other: 0, rate: RATE } }],
    ["groundedness", { groundedness: GROUNDEDNESS_DETAIL }],
  ])("runs triage on this %s finding", async (_kind, detail) => {
    api.getBehaviorFinding.mockResolvedValue(plain({}, detail as Partial<BehaviorFindingDetail>));
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Run triage" }));

    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  // Bug: one story header kept the old inline words, a live button, or a bar in another place than the others.
  it.each([
    ["shift", () => plain({ detector: "cost_drift", ...RUNNING }, { metric: SHIFT }), "What moved"],
    ["rate", () => plain({ detector: "tool_error", ...RUNNING }, { toolError: RATE }), "What moved"],
    ["secret leak", () => plain({ ...RUNNING }, { secretLeak: LEAK }), "When it leaked"],
    ["malformed output", () => plain({ ...RUNNING }, { malformedOutput: { fields: [], notJson: 0, other: 0, rate: RATE } }), "What moved"],
    ["groundedness", () => plain({ ...RUNNING }, { groundedness: GROUNDEDNESS_DETAIL }), "What changed"],
    ["classifier", () => plain({ ...RUNNING }, { classifierDetail: CLASSIFIER_DETAIL }), "What changed"],
    ["generic", () => plain({ ...RUNNING }), null],
  ] as const)("the %s header says Triaging… and the bar sits under it", async (_kind, detail, first) => {
    api.getBehaviorFinding.mockResolvedValue(detail());
    renderPage();

    expect(((await screen.findByRole("button", { name: "Triaging…" })) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Reading flagged answers 51 to 58…")).toBeTruthy();
    const next = first
      ? screen.getByRole("heading", { name: first }).closest("section")!
      : screen.getByText(/This cause carries no measured shift/);
    expect(before(screen.getByRole("heading", { level: 1 }), theBar())).toBe(true);
    expect(before(theBar(), next)).toBe(true);
    if (first) expect(next.className).toContain("border-t");
  });

  it("records a person's verdict from the groundedness header", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({}, { groundedness: GROUNDEDNESS_DETAIL }));
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Absorb as legitimate" }));

    await waitFor(() => expect(api.resolveBehaviorFinding).toHaveBeenCalledWith("fnd-1", "expected"));
  });
});

describe("a frustration finding", () => {
  it.each([
    ["says its sessions were cleared when the case was resolved as a false alarm", true, /Cleared when the case was resolved as a false alarm/],
    ["does not call an empty session list cleared", false, "Each flagged message with the turns before it"],
  ])("%s", async (_, cleared, said) => {
    const conversations = cleared ? [{ traceId: "t-1", conversationId: "c-1", message: "ugh", cleared, contextTraceIds: [] }] : [];
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "frustration" },
        {
          frustration: {
            rate: RATE, arlTarget: 50000, baselineFrustrated: 10, jevThreshold: 0.5, minDecisionInterval: 3600,
            scorerVersion: "v1", conversations, conversationsNextCursor: null,
          } as unknown as FrustrationDetail,
        },
      ),
    );
    renderPage();

    expect(await screen.findByText(said)).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Evidence" })).toBeNull();
  });

  // Bug: the figure became general and reworded frustration's own pins.
  it("tells its rate in sessions with frustration's own words", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      plain(
        { detector: "frustration" },
        {
          frustration: {
            rate: RATE, arlTarget: 50000, baselineFrustrated: 10, jevThreshold: 0.5, minDecisionInterval: 3600,
            scorerVersion: "v1", conversations: [], conversationsNextCursor: null,
          } as unknown as FrustrationDetail,
        },
      ),
    );
    renderPage();

    expect(await screen.findByText("6.40% of sessions are frustrated")).toBeTruthy();
    expect(
      screen.getByText(
        `58 of 906 sessions since ${sinceWords("2026-09-23T09:12:00Z")} had a user frustrated with the agent. This rate opened the case.`,
      ),
    ).toBeTruthy();
    expect(screen.getByText("2.10% is normal for this call site")).toBeTruthy();
    expect(
      screen.getByText(
        "Learned from its first 1,000 sessions. The current rate is 4.3 percentage points higher. A message counts as frustrated when its score is above 0.50.",
      ),
    ).toBeTruthy();
    expect(screen.getByRole("img", { name: "Frustrated sessions: 2.10% before, 6.40% after" })).toBeTruthy();
  });
});

/** How the rate figure writes an onset: day, month and a 24-hour clock. */
const sinceWords = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", hour12: false });

const WORDS: ClassifierDetail["words"] = {
  unitOne: "item", unitMany: "items", figureNote: "Share of items the check flagged",
  referenceLabel: "is the reference", referenceDetail: "Set from the first 1,000 items.",
  thresholdLine: "An item counts when its score is 0.80 or higher.",
};

const CLASSIFIER_DETAIL: ClassifierDetail = {
  rate: RATE, baselineFlagged: 21, flagLabel: "Yes", flagThreshold: 0.8, arlTarget: 50000, minDecisionInterval: null,
  learningUntil: null, words: WORDS,
};

/** A finding its owning adapter details: a title it stored, on a call site. */
const owned = (over: Partial<Finding> = {}, detail: ClassifierDetail = CLASSIFIER_DETAIL) =>
  plain(
    { detector: "item_check", title: "Flagged items on support-agent rose", callSiteId: "support-agent", ...over },
    { classifierDetail: detail },
  );

describe("a finding with a classifier detail", () => {
  // Bug: a detailed finding fell through to the generic layout, with no figure and the verbs below the title, or its
  // verbs sent nothing.
  it("heads the page with its title and one triage button, and tells the rate in its unit", async () => {
    api.getBehaviorFinding.mockResolvedValue(owned());
    renderPage();

    await screen.findByRole("heading", { name: "Flagged items on support-agent rose" });
    expect(screen.getByText("Item check")).toBeTruthy();
    expect(screen.getByText("support-agent")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Run triage" })).toHaveLength(1);
    expect(screen.getByRole("button", { name: "Absorb as legitimate" })).toBeTruthy();
    expect(screen.queryByText(/This cause carries no measured shift/)).toBeNull();

    expect(screen.getByText("What changed")).toBeTruthy();
    expect(screen.getByText("Share of items the check flagged")).toBeTruthy();
    expect(screen.getByText("6.40% of items are flagged")).toBeTruthy();
    expect(screen.getByText(`58 of 906 items since ${sinceWords("2026-09-23T09:12:00Z")} were flagged.`)).toBeTruthy();
    expect(screen.getByText("2.10% is the reference")).toBeTruthy();
    expect(
      screen.getByText(
        "Set from the first 1,000 items. The current rate is 4.3 percentage points higher. An item counts when its score is 0.80 or higher.",
      ),
    ).toBeTruthy();

    fireEvent.click(button("Run triage"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
    fireEvent.click(button("Confirm and open a case"));
    await waitFor(() => expect(api.resolveBehaviorFinding).toHaveBeenCalledWith("fnd-1", "not_expected"));
  });

  // Bug: a ruled finding kept offering verbs that 409, or hid the case its ruling opened.
  it("shows the ruling and its case once triaged", async () => {
    api.getBehaviorFinding.mockResolvedValue(
      owned({
        caseId: "case-9", triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case",
        triageSummary: "The flagged share rose on one prompt change.", triagedAt: "2026-09-23T13:52:00Z",
      }),
    );
    renderPage();

    const toCase = await screen.findByRole("link", { name: "View case" });
    expect(toCase.getAttribute("href")).toBe("/orgs/acme/projects/default/cases/case-9");
    expect(screen.getByText("Positive")).toBeTruthy();
    expect(screen.getByText("The flagged share rose on one prompt change.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Run triage" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Absorb as legitimate" })).toBeNull();
    expect(screen.queryByRole("heading", { name: "What triage ruled" })).toBeNull();
  });

  // Bug: the raw witness rows listed the flagged traces again as bare ids under the figure.
  it("shows no evidence table", async () => {
    api.getBehaviorFinding.mockResolvedValue(owned());
    renderPage();

    await screen.findByText("6.40% of items are flagged");
    expect(screen.queryByRole("heading", { name: "Evidence" })).toBeNull();
    expect(api.getBehaviorFindingEvidence).not.toHaveBeenCalled();
  });

  // Bug: the open page put another story's words (sessions, answers, a learned normal) around a classifier's rate.
  it("draws every figure word from the detail's words and none of its own", async () => {
    const fell = { ...RATE, curRate: 0.021, refRate: 0.064, deltaPp: -4.3, direction: "down" };
    api.getBehaviorFinding.mockResolvedValue(
      owned({ title: "Flagged widgets on support-agent fell" }, {
        ...CLASSIFIER_DETAIL,
        rate: fell,
        words: {
          unitOne: "widget", unitMany: "widgets", figureNote: "Share of widgets the check marked",
          referenceLabel: "is the agreed limit", referenceDetail: null, thresholdLine: null,
        },
      }),
    );
    renderPage();

    expect(await screen.findByText("Share of widgets the check marked")).toBeTruthy();
    expect(screen.getByText("2.10% of widgets are flagged")).toBeTruthy();
    expect(screen.getByText(`58 of 906 widgets since ${sinceWords("2026-09-23T09:12:00Z")} were flagged.`)).toBeTruthy();
    expect(screen.getByText("6.40% is the agreed limit")).toBeTruthy();
    expect(screen.getByText("The current rate is 4.3 percentage points lower.")).toBeTruthy();
    expect(screen.getByRole("img", { name: "Flagged widgets: 6.40% before, 2.10% after" })).toBeTruthy();
    for (const word of [/items?\b/, /session/, /answer/, /frustrat/, /normal for this call site/, /Learned from/]) {
      expect(screen.queryAllByText(word)).toHaveLength(0);
    }
  });
});

describe("FindingPage, groundedness", () => {
  beforeEach(() => api.getBehaviorFinding.mockResolvedValue(GROUNDEDNESS_FINDING_DETAIL));

  it("tells the rate and shows the flagged answers instead of the evidence table", async () => {
    renderPage();

    await screen.findByText("Answers on support-agent became less grounded");
    const since = dateTime("2026-09-23T09:12:00Z");
    expect(screen.getByText(`since ${since} vs the rate it learned from its first 1,000 traces`)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Run triage" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Absorb as legitimate" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Confirm and open a case" })).toBeTruthy();

    expect(screen.getByText("What changed")).toBeTruthy();
    expect(screen.getByText("Share of traces with a flagged answer")).toBeTruthy();
    expect(screen.getByText("6.4% of answers flagged")).toBeTruthy();
    expect(
      screen.getByText(
        `58 of 906 traces since ${since} had an answer flagged as unsupported. This rate opened the finding.`,
      ),
    ).toBeTruthy();
    expect(screen.getByText("2.1% is normal for this call site")).toBeTruthy();
    expect(
      screen.getByText(
        "Learned from its first 1,000 traces. The current rate is 4.3 percentage points higher. An answer is flagged when one of its sentences scores 0.975 or higher.",
      ),
    ).toBeTruthy();

    expect(screen.getByText("Marked sentences scored 0.975 or higher")).toBeTruthy();
    expect(within(screen.getByRole("region", { name: "Answer" })).getAllByRole("mark")).toHaveLength(2);
    expect(screen.getByText("1 of 58 traces")).toBeTruthy();
    expect(screen.queryByText("Evidence")).toBeNull();
    expect(api.getBehaviorFindingEvidence).not.toHaveBeenCalled();
  });

  it("shows a triaged finding's ruling and its case in place of the triage verbs", async () => {
    api.getBehaviorFinding.mockResolvedValue({
      ...GROUNDEDNESS_FINDING_DETAIL,
      finding: {
        ...GROUNDEDNESS_FINDING_DETAIL.finding,
        caseId: "case-1", status: "closed", triageStatus: "done", triageVerdict: "positive",
        triageAction: "opened_case", triagedAt: "2026-09-23T13:52:00Z",
      },
    });
    renderPage();

    const toCase = await screen.findByRole("link", { name: "View case" });
    expect(toCase.getAttribute("href")).toBe("/orgs/acme/projects/default/cases/case-1");
    expect(screen.getByText("Positive")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Run triage" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Confirm and open a case" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Absorb as legitimate" })).toBeNull();
  });
});
