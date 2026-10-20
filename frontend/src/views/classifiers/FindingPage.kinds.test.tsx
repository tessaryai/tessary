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
import { api } from "../../test/api";

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
    attempts: 1, maxAttempts: 5, startedAt: null, heldBy: null, ...over,
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

  // Bug: a run held on a rejected key read as "Triaging" for ever, with nothing saying what to fix.
  it.each([
    ["CLASSIFIER.TRIAGE_PROVIDER_REJECTED", /The provider rejected the stored key/],
    ["CLASSIFIER.TRIAGE_REQUEST_REFUSED", /The provider refused the request/],
    ["MODEL_CONFIG.MISSING_CREDENTIALS", /There is no key for the provider triage runs on/],
    ["CLASSIFIER.TRIAGE_LAUNCHER_MISCONFIGURED", /The sandbox launcher refused the run/],
    ["CLOUD.SOMETHING_NEW", /Triage is waiting for a setting to change/],
  ])("says what to fix when a run is held on %s, and offers to run it again", async (heldBy, words) => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus: "blocked", triageProgress: progress({ heldBy }) }));
    renderPage();

    expect(await screen.findByText(words)).toBeTruthy();
    expect(screen.getByText("seen 58× · triage blocked")).toBeTruthy();
    fireEvent.click(button("Run triage again"));
    await waitFor(() => expect(api.analyzeBehaviorFinding).toHaveBeenCalledWith("fnd-1"));
  });

  // Bug: a run that gave up said only that it failed, not that it had already been tried.
  it("says how many times a run that gave up was tried, and offers to run it again", async () => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus: "failed", triageProgress: progress({ attempts: 5 }) }));
    renderPage();

    expect(await screen.findByText("Triage tried 5 times and gave up.")).toBeTruthy();
    expect(button("Run triage again").disabled).toBe(false);
  });

  // Bug: a run waiting for the worker, or for its next attempt, read the same as one running.
  it.each([
    ["queued", progress({ attempts: 0 }), "Queued…", /Waiting for the triage worker/],
    ["retrying", progress({ attempts: 1 }), "Retrying…", /Attempt 1 of 5 ended with no ruling/],
    ["in_flight", progress({ attempts: 2, startedAt: new Date(Date.now() - 120_000).toISOString() }), "Triaging…",
      /Started 2m ago, attempt 2 of 5/],
  ] as const)("says where a %s run stands and keeps the button off", async (triageStatus, triageProgress, label, words) => {
    api.getBehaviorFinding.mockResolvedValue(plain({ triageStatus, triageProgress }));
    renderPage();

    expect(await screen.findByText(words)).toBeTruthy();
    expect(button(label).disabled).toBe(true);
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

describe("the triage verbs in each story's header", () => {
  const T0 = "2026-01-06T10:00:00Z";
  it.each([
    [
      "secret leak",
      {
        secretLeak: {
          basis: "Any high-confidence match opens a case.", confidence: "high", firstAt: T0, lastAt: T0,
          keys: [{ lastAt: T0, leaks: 1, masked: "sk-a…9f2c", storedRaw: true, traces: 1 }],
          leakCount: 1,
          leaks: [{ at: T0, masked: "sk-a…9f2c", spanId: "span-1", stored: "raw", traceId: "trace-1" }],
          rule: "openai_api_key", threshold: 1, traceCount: 1, windowEnd: T0, windowSeconds: 86400, windowStart: T0,
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
