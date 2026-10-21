// SPDX-License-Identifier: Apache-2.0
/*
 * CasePage: the groundedness story's causes, rate and cause-filtered answers, the verbs that close a
 * case, the RCA run and its in-flight and failed states, the answer and the working behind it, the
 * failures a page at a time, and a frustration case's causes. Reports in the older stored shape and in the
 * current one both render. The bugs worth catching: a verb sent for
 * the wrong case or offered before there is a report to justify it, a run that can be pressed twice, a
 * failed run that reads as an empty one, and a cause's "Show" that filters nothing.
 */
import { beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type {
  CaseDetail, ClassifierDetail, FlaggedAnswer, FrustratedConversation, FrustrationDetail, RcaReport, RcaRun,
} from "../../api/types";
import { ApiError } from "../../api/types";
import type { CapabilitiesView, Me } from "../../api/types-auth";
import { AuthProvider } from "../../auth/AuthContext";
import { renderRoute } from "../../test/render";
import { CasePage } from "./CasePage";
import { RUN_POLL_MS } from "../components/runWords";
import { stamp } from "../classifiers/shared";
import { FLAGGED_LIST_ID } from "../classifiers/classifierStory";
import {
  CURRENT_GROUNDEDNESS_REPORT, FLAGGED_ANSWER, GROUNDEDNESS_CASE_DETAIL, GROUNDEDNESS_DETAIL, GROUNDEDNESS_REPORT,
} from "../../test/groundednessFixtures";
import { api, auth } from "../../test/api";
import { stepsPage, TRIAGE_STEPS } from "../../test/runFixtures";

const OWNER: Me = {
  id: "user-1", email: "owner@example.com", orgs: [{ id: "org-1", slug: "acme", name: "Acme", role: "owner" }],
  platform_staff: false,
};

vi.mock("../../api/client", () => import("../../test/api"));
vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

// ---- fixtures -------------------------------------------------------------------------------

const BASE = GROUNDEDNESS_CASE_DETAIL;

/** A case whose classifier draws no figure and ranks no causes: the answer-and-working layout. */
function plainCase(over: Partial<CaseDetail> = {}, caseOver: Partial<CaseDetail["case"]> = {}): CaseDetail {
  return {
    ...BASE, groundedness: null, rca: null, rca_report_id: null, ...over,
    case: { ...BASE.case, detector: "loop", rca_verdict: null, ...caseOver },
  };
}

const ANSWER_REPORT: RcaReport = {
  ...GROUNDEDNESS_REPORT, report_kind: "attribution", verdict: "model_change",
  summary: "The call site got slower because the serving model changed and inputs grew.",
  causes: [
    {
      title: "The serving model changed under the call site", confidence: "high", change: null, type: null,
      what_changed: "A new model version started serving this call site.",
      how_it_caused_this: "The new model takes longer on the same inputs.",
      next_step: "A real regression. Pin the previous model.", attribution: null,
      evidence_trace_ids: ["trace-0123456789"],
      evidence_session_ids: [],
      affected_count: 12,
    },
    {
      title: "Customers started pasting whole order histories", confidence: "high", change: null, type: null,
      what_changed: "Inputs grew to include order histories.", how_it_caused_this: "Longer inputs take longer to read.",
      next_step: "Expected traffic. Nothing to fix.", attribution: null, evidence_trace_ids: [],
      evidence_session_ids: [], affected_count: 4,
    },
    {
      title: "Traffic moved to longer inputs", confidence: "low", change: null, type: null,
      what_changed: "Inputs grew.", how_it_caused_this: "Maybe slower.",
      next_step: "Compare input lengths on both sides.", attribution: null, evidence_trace_ids: [],
      evidence_session_ids: [], affected_count: 0,
    },
  ],
  ruled_out: [
    {
      check: "model_swap", question: "Did the serving model change?", assessment: "explains",
      detail: "Model id changed.", measurement: null, passed: false,
    },
    { check: "input_length", question: null, assessment: "contributing", detail: "Longer inputs.", measurement: null, passed: false },
    { check: "grader_drift", question: null, assessment: "ruled_out", detail: "Grader unchanged.", measurement: null, passed: true },
    { check: "traffic_mix", question: null, assessment: "unknown", detail: "Not measured.", measurement: null, passed: false },
  ],
};

const LEADS_ONLY = ANSWER_REPORT.causes.map((k) => ({ ...k, confidence: "medium" }));

const conversation = (id: string, message: string): FrustratedConversation =>
  ({
    traceId: id,
    sessionId: `sess-${id}`,
    conversationId: `conv-${id}`,
    callSiteId: "support-agent", contextTraceIds: [], flaggedAt: "2026-09-23T14:00:00Z", score: 0.9, message,
    cleared: false,
  }) as FrustratedConversation;

const FRUSTRATION: FrustrationDetail = {
  rate: { ...GROUNDEDNESS_DETAIL.rate, failuresCur: 40 }, arlTarget: 50000, baselineFrustrated: 10,
  conversations: [conversation("f-all", "This is the third time I asked.")],
  conversationsNextCursor: null, jevThreshold: 0.5, minDecisionInterval: 3600, scorerVersion: "v1",
} as FrustrationDetail;

const FRUSTRATION_REPORT: RcaReport = {
  ...GROUNDEDNESS_REPORT,
  id: "rca-f",
  report_kind: "frustration_causes",
  causes: [
    {
      ...GROUNDEDNESS_REPORT.causes[0],
      title: "The agent loops on refund questions",
      attribution: { kind: "prompt", path: "agent/prompt.md", commit: "c0ffee1234", excerpt: "Always retry." },
      evidence_session_ids: ["s-1", "s-1"],
    },
    { ...GROUNDEDNESS_REPORT.causes[1], evidence_session_ids: [] },
  ],
};

const frustrationCase = (over: Partial<CaseDetail> = {}): CaseDetail => ({
  ...BASE, groundedness: null, frustration: FRUSTRATION, rca: FRUSTRATION_REPORT, rca_report_id: "rca-f", ...over,
  case: { ...BASE.case, detector: "frustration", title: "Users on support-agent grew frustrated" },
});

const WINDOW_OPENED = "2026-10-09T10:26:48Z";
const WINDOW_CLOSED = "2026-10-09T15:52:33Z";
const CASE_OPENED = "2026-10-09T16:07:10Z";

const SHIFT = {
  bucketKey: "support-agent", bucketKind: "call_site", nCur: 500, nRef: 1200, sinceVersionId: null,
  windowClosedAt: WINDOW_CLOSED, windowKind: null, windowOpenedAt: WINDOW_OPENED, w1Log: 0.4, control: null,
  direction: "up", explains: [], floor: 0, measure: "duration",
  quantiles: [{ key: "p50", then: 1000, now: 2500 }, { key: "p95", then: 4000, now: 9000 }],
  ratio: 2.5, reference: "previous", tokens: [], workload: [],
} as unknown as NonNullable<CaseDetail["metric"]>;

/** A drift case as the server writes it: onset is the sample that closed the window, last seen is the wall clock. */
const driftCase = (metric: Partial<NonNullable<CaseDetail["metric"]>> = {}): CaseDetail =>
  plainCase(
    { metric: { ...SHIFT, ...metric } },
    { detector: "duration_drift", onset_at: WINDOW_CLOSED, last_seen_at: CASE_OPENED },
  );

// ---- harness --------------------------------------------------------------------------------

beforeEach(() => {
  auth.me.mockResolvedValue(OWNER);
  auth.getCapabilities.mockResolvedValue({ capabilities: { rca_enabled: true } as CapabilitiesView["capabilities"] });
  api.getCase.mockResolvedValue(BASE);
  api.getGitIntegration.mockResolvedValue({ provider: "github" });
  api.getFlaggedAnswers.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getFrustratedSessions.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getBehaviorFindingEvidence.mockResolvedValue({ rows: [], nextCursor: null, counts: {}, recordedCounts: {} });
  api.getTrace.mockReturnValue(new Promise(() => {}));
  for (const f of [api.closeCase, api.absorbCase, api.runCaseRca, api.rerunRca])
    f.mockResolvedValue({});
});

const renderPage = () =>
  renderRoute(
    <AuthProvider>
      <CasePage />
    </AuthProvider>,
    { route: "/orgs/acme/projects/default/cases/case-1", path: "/orgs/:orgSlug/projects/:projectSlug/cases/:caseId" },
  );

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const heading = (name: string) => screen.findByRole("heading", { level: 1, name });
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const dialog = () => screen.getByRole("dialog");

// ---- tests ----------------------------------------------------------------------------------

describe("closing a case", () => {
  it("closes the case after the confirmation, then dismisses the dialog", async () => {
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Close"));
    expect(within(dialog()).getByText("The evidence in this case's findings is left out when the baseline is set.")).toBeTruthy();
    fireEvent.click(within(dialog()).getByRole("button", { name: "Close case" }));

    await waitFor(() => expect(api.closeCase).toHaveBeenCalledWith("case-1"));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(api.getCase).toHaveBeenCalledTimes(2);
  });

  it.each([
    ["Close", "Cancel", api.closeCase],
    ["Close", "Close", api.closeCase],
    ["Absorb", "Cancel", api.absorbCase],
    ["Absorb", "Close", api.absorbCase],
  ])("dismisses the %s dialog on %s without sending it", async (verb, dismiss, send) => {
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button(verb));
    fireEvent.click(within(dialog()).getByRole("button", { name: dismiss }));
    expect(screen.queryByRole("dialog")).toBeNull();

    await settle();
    expect(send).not.toHaveBeenCalled();
  });

  it("absorbs the case after the confirmation, and says why an absorb was refused", async () => {
    api.absorbCase.mockRejectedValueOnce(new ApiError(409, { code: "CASE.LOCKED", message: "Case is locked" }));
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Absorb"));
    expect(within(dialog()).getByText("The evidence in this case's findings counts toward the baseline.")).toBeTruthy();
    const confirmAbsorb = () => within(dialog()).getByRole("button", { name: "Absorb" });
    fireEvent.click(confirmAbsorb());
    expect(await within(dialog()).findByText("CASE.LOCKED")).toBeTruthy();

    fireEvent.click(confirmAbsorb());
    await waitFor(() => expect(api.absorbCase).toHaveBeenCalledTimes(2));
    expect(api.absorbCase).toHaveBeenLastCalledWith("case-1");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("offers Close alone where a case cannot be absorbed", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1", absorb_available: false }));
    renderPage();
    await heading(BASE.case.title);

    expect(button("Close")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Absorb" })).toBeNull();
  });

  it.each([
    ["fixed", "Closed as fixed"],
    ["false_alarm", "Closed as a false alarm"],
    [null, "Closed"],
  ])("offers no verbs on a closed case, and says who closed it and why (%s)", async (disposition, closed) => {
    api.getCase.mockResolvedValue({
      ...BASE,
      case: {
        ...BASE.case,
        state: "resolved", resolved_at: "2026-09-23T15:00:00Z", resolved_by: "dana@example.com",
        resolution_reason: "prompt fixed", disposition,
      },
    });
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(new RegExp(`^${closed} (?!as ).* by dana@example.com: prompt fixed$`))).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^(Close|Absorb|Re-run RCA)$/ })).toBeNull();
  });

  it("makes a case read-only once its classifier is gone, and says why", async () => {
    api.getCase.mockResolvedValue({ ...BASE, detector_available: false });
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(/is no longer available to this organization, so this case is read-only/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^(Close|Absorb|Re-run RCA)$/ })).toBeNull();
  });
});

it("the meta line: names the classifier as the classifiers page does, and no call site for the unattributed sentinel", async () => {
  api.getCase.mockResolvedValue(plainCase({}, { detector: "tool_error", call_site_id: "__unattributed__" }));
  renderPage();
  await heading(BASE.case.title);

  expect(screen.getByText("Tool error")).toBeTruthy();
  expect(screen.queryByText("__unattributed__")).toBeNull();
});

describe("a metric-drift case's window", () => {
  // Bug: the header read the case's onset (the sample that closed the window) and its wall-clock last
  // seen, so a five-hour window showed as fifteen minutes on two different clocks.
  // A half-known window must not pair the window's open with the case's wall-clock last seen.
  it.each([
    ["spans the window the drift was measured over", {}, [WINDOW_OPENED, WINDOW_CLOSED]],
    ["falls back to the case's own times when the finding carries no window", { windowOpenedAt: null, windowClosedAt: null }, [WINDOW_CLOSED, CASE_OPENED]],
    ["takes both ends from the case when only one end of the window is known", { windowClosedAt: null }, [WINDOW_CLOSED, CASE_OPENED]],
  ])("%s", async (_case, metric, [from, to]) => {
    api.getCase.mockResolvedValue(driftCase(metric));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(`${stamp(from)} → ${stamp(to)}`)).toBeTruthy();
  });
});

describe("running RCA", () => {
  it("runs RCA for this case, and withholds Close and Absorb until there is a report", async () => {
    api.getCase.mockResolvedValue(plainCase());
    api.runCaseRca.mockReturnValue(new Promise(() => {}));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByRole("button", { name: "Close" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Absorb" })).toBeNull();
    fireEvent.click(button("Run RCA"));

    await waitFor(() => expect(api.runCaseRca).toHaveBeenCalledWith("case-1"));
    expect(button("Analyzing…").disabled).toBe(true);
  });

  /** Catches "Re-run RCA" coalescing onto the finished report, so the press starts nothing. */
  it("re-runs the case's report when one exists, instead of asking the case trigger again", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1" }));
    api.getRcaReport.mockResolvedValue(ANSWER_REPORT);
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Re-run RCA"));

    await waitFor(() => expect(api.rerunRca).toHaveBeenCalledWith("rca-1"));
    expect(api.runCaseRca).not.toHaveBeenCalled();
  });

  it("says the finding is gone when the analysis cannot find it, and names any other refusal", async () => {
    api.getCase.mockResolvedValue(plainCase());
    api.runCaseRca
      .mockRejectedValueOnce(new ApiError(404, { code: "RCA.SUBJECT_NOT_FOUND", message: "gone" }))
      .mockRejectedValueOnce(new ApiError(429, { code: "RCA.BUSY", message: "busy" }));
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Run RCA"));
    expect(await screen.findByText(/The finding behind this case is no longer there/)).toBeTruthy();
    fireEvent.click(button("Run RCA"));
    expect(await screen.findByText("RCA.BUSY")).toBeTruthy();
  });

  it("polls a queued run until it finishes, then shows its answer", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.getCase.mockResolvedValue(plainCase({ rca_report_id: "rca-1" }));
      api.getRcaReport
        .mockResolvedValueOnce({ ...ANSWER_REPORT, status: "pending", run: rcaRun({ state: "queued" }) })
        .mockResolvedValue(ANSWER_REPORT);
      renderPage();
      await heading(BASE.case.title);

      await waitFor(() => expect(api.getRcaReport).toHaveBeenCalledWith("rca-1"));
      expect(button("Analyzing…").disabled).toBe(true);
      await act(() => vi.advanceTimersByTimeAsync(5000));

      expect(await screen.findByText("The serving model changed under the call site")).toBeTruthy();
      expect(button("Re-run RCA").disabled).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it("opens the repository dialog from beside Run RCA while the project has none", async () => {
    api.getGitIntegration.mockResolvedValue(null);
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(await screen.findByRole("button", { name: "Connect repository" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

const rcaRun = (over: Partial<RcaRun> = {}): RcaRun => ({
  state: "running", queued_at: new Date(Date.now() - 400_000).toISOString(), run_id: "run-9",
  started_at: new Date(Date.now() - 358_000).toISOString(), ended_at: null, step_count: 13,
  doing: "Comparing the index sources before and after commit a41c0de", last_step: null, last_step_ms: null, note: null,
  note_ms: null, stop_code: null, ...over,
});

/** The case while its report runs: the case read inlines no report, so the page reads it until it ends. */
const runningCase = () => plainCase({ rca_report_id: "rca-1" }, { rca_run_state: "running" });

const markName = /^(Queued|Running|Stopped)$/;
const theBar = () => screen.getByRole("img", { name: markName }).closest("div.rounded-card")!;
const follows = (a: Element, b: Element) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

describe("the RCA run bar", () => {
  // Bug: the bar sat somewhere other than after the header, or the old Analyzing card stayed beside it.
  it.each([
    ["queued", { ...ANSWER_REPORT, status: "pending", run: rcaRun({ state: "queued", run_id: null, started_at: null, step_count: 0, doing: null }) },
      "Queued", "Queued. It starts when a worker is free."],
    ["running", { ...ANSWER_REPORT, status: "claimed", run: rcaRun() }, "Running",
      "Comparing the index sources before and after commit a41c0de…"],
  ] as const)("the bar shows after the header while RCA is %s", async (_state, polled, mark, line) => {
    api.getCase.mockResolvedValue(runningCase());
    api.getRcaReport.mockResolvedValue(polled);
    renderPage();

    expect(await screen.findByText(line)).toBeTruthy();
    expect(screen.getByRole("img", { name: mark })).toBeTruthy();
    expect(follows(screen.getByRole("banner"), theBar())).toBe(true);
    expect(follows(theBar(), screen.getByRole("heading", { name: "How big" }))).toBe(true);
    expect(screen.queryByText("Reading the evidence and looking for the cause.")).toBeNull();
    expect(button("Analyzing…").disabled).toBe(true);
  });

  // Bug: the step and its time were missing from a running bar, or its steps were read before anyone asked.
  it("a running bar names its step and time, and reads steps only once opened", async () => {
    api.getCase.mockResolvedValue(runningCase());
    api.getRcaReport.mockResolvedValue({ ...ANSWER_REPORT, status: "claimed", run: rcaRun() });
    api.getRcaSteps.mockResolvedValue(stepsPage(TRIAGE_STEPS, { runId: "run-9" }));
    renderPage();

    expect(await screen.findByText("step 13 · 5m 58s")).toBeTruthy();
    expect(api.getRcaSteps).not.toHaveBeenCalled();
    fireEvent.click(button("Show steps"));
    expect(await screen.findByText("Read the finding and its numbers")).toBeTruthy();
    expect(api.getRcaSteps).toHaveBeenCalledWith("rca-1", 0);
  });

  // Bug: the page said nothing while the report's first read was still on its way.
  it("says the run is queued from the case read before the report arrives", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca_report_id: "rca-1" }, {
      rca_run_state: "queued", rca_run_since: new Date(Date.now() - 4_000).toISOString(),
    }));
    api.getRcaReport.mockReturnValue(new Promise(() => {}));
    renderPage();

    expect(await screen.findByText("Queued. It starts when a worker is free.")).toBeTruthy();
    expect(screen.getByText("0:04")).toBeTruthy();
  });

  // Bug: a stopped RCA could not say why, nor where to fix it.
  it("a stopped RCA names its cause and the fix", async () => {
    auth.getCapabilities.mockResolvedValue({
      capabilities: { rca_enabled: true, byo_provider_keys_enabled: true } as CapabilitiesView["capabilities"],
    });
    api.getRcaSteps.mockResolvedValue(stepsPage(TRIAGE_STEPS.slice(0, 6), { runId: "run-9" }));
    api.getCase.mockResolvedValue(plainCase({
      rca_report_id: "rca-1",
      rca: {
        ...ANSWER_REPORT, status: "failed",
        run: rcaRun({ state: "stopped", step_count: 6, doing: null, ended_at: new Date().toISOString(), stop_code: "RCA.PROVIDER_REJECTED" }),
      },
    }));
    renderPage();

    expect(
      await screen.findByText("Stopped at step 6: the provider rejected the stored key. Fix the key, then run RCA again."),
    ).toBeTruthy();
    expect(screen.getByRole("img", { name: "Stopped" })).toBeTruthy();
    expect((await screen.findByRole("link", { name: "Open Providers" })).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/settings/providers",
    );
    expect(button("Re-run RCA").disabled).toBe(false);
    fireEvent.click(button("Show steps"));
    expect(await screen.findByText("Wrote checks/unsupported_terms.py")).toBeTruthy();
    expect(api.getRcaSteps).toHaveBeenCalledWith("rca-1", 0);
  });

  // Bug: the page kept reading the report every few seconds after the run ended, and the list kept its stale mark.
  it("the case page stops reading the report once the run ends", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.getCase.mockResolvedValueOnce(runningCase()).mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1" }));
      api.getRcaReport
        .mockResolvedValueOnce({ ...ANSWER_REPORT, status: "claimed", run: rcaRun() })
        .mockResolvedValue({ ...ANSWER_REPORT, run: rcaRun({ state: "done", ended_at: new Date().toISOString(), doing: null }) });
      renderPage();
      expect(await screen.findByRole("img", { name: "Running" })).toBeTruthy();

      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS));

      expect(await screen.findByText("The serving model changed under the call site")).toBeTruthy();
      await waitFor(() => expect(api.getCase).toHaveBeenCalledTimes(2));
      expect(screen.queryByRole("img", { name: markName })).toBeNull();
      const reads = api.getRcaReport.mock.calls.length;
      await act(() => vi.advanceTimersByTimeAsync(RUN_POLL_MS * 3));
      expect(api.getRcaReport.mock.calls.length).toBe(reads);
    } finally {
      vi.useRealTimers();
    }
  });

  // Bug: once the analysis landed, the steps that reached it were gone with the bar.
  it("an analyzed case keeps a fold with its step count", async () => {
    api.getRcaSteps.mockResolvedValue(stepsPage(TRIAGE_STEPS.map((step) => ({ ...step, ended_ms: 500_000, ok: true })), { runId: "run-9" }));
    api.getCase.mockResolvedValue(plainCase({
      rca_report_id: "rca-1",
      rca: {
        ...ANSWER_REPORT,
        run: rcaRun({
          state: "done", step_count: 23, doing: null, started_at: "2026-09-23T14:00:00Z", ended_at: "2026-09-23T14:09:00Z",
        }),
      },
    }));
    renderPage();

    const fold = await screen.findByRole("button", { name: "Did 23 steps in 9m" });
    expect(follows(screen.getByRole("link", { name: "Read the full analysis" }), fold)).toBe(true);
    expect(screen.queryByRole("img", { name: markName })).toBeNull();
    fireEvent.click(fold);
    expect(await screen.findByText("Ran checks/unsupported_terms.py")).toBeTruthy();
    expect(api.getRcaSteps).toHaveBeenCalledWith("rca-1", 0);
  });

  // Bug: the page changed when nothing runs.
  it("a case never analyzed shows no bar", async () => {
    api.getCase.mockResolvedValue(plainCase());
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByRole("img", { name: markName })).toBeNull();
    expect(button("Run RCA").disabled).toBe(false);
    expect(api.getRcaReport).not.toHaveBeenCalled();
  });
});

describe("the answer and the working behind it", () => {
  /** Catches a second proven cause hidden behind the first, and a lead shown beside proven causes as if it were one. */
  it("cards every proven cause with labelled rows, keeps leads off the page, and asks each check as a question", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("The serving model changed under the call site")).toBeTruthy();
    expect(screen.getByText("Customers started pasting whole order histories")).toBeTruthy();
    expect(screen.queryByText("Traffic moved to longer inputs")).toBeNull();
    expect(screen.getAllByText("What changed")).toHaveLength(2);
    expect(screen.getAllByText("Why it changed")).toHaveLength(2);
    expect(screen.getAllByText("What it means")).toHaveLength(2);
    expect(screen.getByText("The new model takes longer on the same inputs.")).toBeTruthy();
    expect(screen.getByText("12 flagged traces")).toBeTruthy();
    expect(screen.getByRole("link", { name: "trace-01…" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/trace-0123456789",
    );
    expect(screen.getByRole("link", { name: "Read the full analysis" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/rca/rca-1",
    );
    expect(screen.queryByText("No cause proven")).toBeNull();

    expect(screen.getByText("4 explanations tested, 1 eliminated.")).toBeTruthy();
    expect(screen.getByText("Did the serving model change?")).toBeTruthy();
    expect(screen.getByText("model_swap")).toBeTruthy();
    const assessments = ["explains", "contributing", "ruled out", "unknown"].map(
      (a) => (screen.getByText(a) as HTMLElement).style.color,
    );
    expect(assessments).toEqual(["var(--color-error)", "var(--color-warning)", "var(--color-subtle)", "var(--color-muted)"]);
  });

  /** Catches leads presented as the answer when nothing was proven. */
  it("says no cause is proven, gives the summary, and labels every lead as a lead", async () => {
    api.getCase.mockResolvedValue(
      plainCase({ rca: { ...ANSWER_REPORT, causes: LEADS_ONLY, verdict: "inconclusive" }, rca_report_id: "rca-1" }),
    );
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("No cause proven")).toBeTruthy();
    expect(screen.getByText(ANSWER_REPORT.summary!)).toBeTruthy();
    expect(screen.getByText("Traffic moved to longer inputs")).toBeTruthy();
    expect(screen.getAllByText("Why it might be")).toHaveLength(3);
    expect(screen.getAllByText("To confirm")).toHaveLength(3);
    expect(screen.queryByText("What it means")).toBeNull();
  });

  /** Catches an empty Why block, or the raw reply, when the run wrote no summary and found no cause. */
  it("falls back to the verdict when the run found no cause and wrote no summary", async () => {
    api.getCase.mockResolvedValue(
      plainCase({ rca: { ...ANSWER_REPORT, causes: [], ruled_out: [], summary: null }, rca_report_id: "rca-1" }),
    );
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("No cause proven")).toBeTruthy();
    expect(screen.getByText("Serving model changed")).toBeTruthy();
    expect(screen.queryByText("What else was checked")).toBeNull();
  });

  // Bug: a failed run read as an empty answer. A report that failed before runs were recorded carries no run or code,
  // so its bar says the agent ended without an answer.
  it("says a failed run did not finish, rather than showing an empty answer", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: { ...ANSWER_REPORT, status: "failed" }, rca_report_id: "rca-1" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("Stopped: the agent ended without an answer. Run RCA again.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /steps/ })).toBeNull();
    expect(screen.queryByText("The serving model changed under the call site")).toBeNull();
    expect(screen.queryByText("What else was checked")).toBeNull();
  });

  /** Catches the summary repeated under the headline, where it competed with the Why block. */
  it("does not repeat the summary under the headline", async () => {
    api.getCase.mockResolvedValue(plainCase({}, { rca_verdict: "inconclusive", cause: "a model rollout" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByText(/a model rollout/)).toBeNull();
  });

  /** Catches a current medium cause hidden as a lead, labels taken from the case type, and an internal id shown
   *  beside a ruled-out sentence. */
  it("cards a current report's medium cause as a cause, labels each from its change, and lists what was ruled out", async () => {
    api.getCase.mockResolvedValue(
      plainCase({ rca: { ...CURRENT_GROUNDEDNESS_REPORT, report_kind: "attribution" }, rca_report_id: "rca-1" }),
    );
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("The retriever still serves the old pricing and policy pages")).toBeTruthy();
    expect(screen.getByText("The system prompt asks for a complete answer every time")).toBeTruthy();
    expect(screen.queryByText("No cause proven")).toBeNull();
    expect(screen.queryByText(CURRENT_GROUNDEDNESS_REPORT.summary!)).toBeNull();
    expect(screen.getByText("High")).toBeTruthy();
    expect(screen.getByText("Medium")).toBeTruthy();
    expect(screen.getByText("What changed")).toBeTruthy();
    expect(screen.getByText("What the agent does")).toBeTruthy();
    expect(screen.getByText("Why it changed")).toBeTruthy();
    expect(screen.getByText("Why it happens")).toBeTruthy();
    expect(screen.getAllByText("What to do")).toHaveLength(2);
    expect(screen.queryByText("Why it might be")).toBeNull();
    expect(screen.queryByText("To confirm")).toBeNull();
    expect(screen.queryByText("What it means")).toBeNull();
    expect(screen.getByText("Data")).toBeTruthy();
    expect(screen.getByText("Prompt")).toBeTruthy();
    expect(screen.getByText("Always give a complete answer.")).toBeTruthy();

    expect(screen.getByText("2 explanations tested, 2 eliminated.")).toBeTruthy();
    expect(screen.getByText("The serving model did not change during the window.")).toBeTruthy();
    expect(screen.getAllByText("ruled out")).toHaveLength(2);
    expect(screen.queryByText("ruled_out_1")).toBeNull();
  });
});

describe("a case with several findings", () => {
  const findings = [
    { id: "fnd-0", title: "turns are 1.77x slower", window_opened_at: "2026-09-20T08:00:00Z", window_closed_at: "2026-09-21T08:00:00Z", created_at: "2026-09-21T08:05:00Z" },
    { id: "fnd-1", title: "turns are 1.69x slower", window_opened_at: "2026-09-22T08:00:00Z", window_closed_at: "2026-09-23T08:00:00Z", created_at: "2026-09-23T08:05:00Z" },
  ];

  it("spans the header from the first window and lists every window with its finding", async () => {
    api.getCase.mockResolvedValue(plainCase({ findings }));
    renderPage();
    await heading(BASE.case.title);

    // The header and the first window's row both open on the first window.
    expect(screen.getAllByText(new RegExp(`^${escape(stamp("2026-09-20T08:00:00Z"))} →`))).toHaveLength(2);
    expect(screen.getByText("turns are 1.77x slower")).toBeTruthy();
    const links = screen.getAllByRole("link", { name: "Open finding" });
    expect(links.map((l) => l.getAttribute("href"))).toEqual([
      expect.stringContaining("fnd-0"),
      expect.stringContaining("fnd-1"),
    ]);
  });

  it("no longer lists the failing calls", async () => {
    api.getCase.mockResolvedValue(plainCase({ findings }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByText("The failures")).toBeNull();
    expect(api.getBehaviorFindingEvidence).not.toHaveBeenCalled();
  });
});

function escape(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

it("the activity: stays collapsed until asked, then lists each event and who did it", async () => {
  api.getCase.mockResolvedValue({
    ...BASE,
    events: [
      { id: "e1", kind: "opened", summary: "Case opened", actor: null, created_at: "2026-09-23T13:52:00Z" },
      { id: "e2", kind: "muted", summary: "Muted", actor: "dana@example.com", created_at: "2026-09-23T14:00:00Z" },
    ],
  });
  renderPage();
  const toggle = await screen.findByRole("button", { name: "Activity (2) +" });
  expect(screen.queryByText("Case opened")).toBeNull();

  fireEvent.click(toggle);

  expect(screen.getByText("Case opened").parentElement!.textContent).toContain("Tessary");
  expect(screen.getByText("dana@example.com")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Activity (2) −" }));
  expect(screen.queryByText("Case opened")).toBeNull();
});

describe("a frustration case", () => {
  it("cards its proven cause, shows where in the repo, and filters the sessions to the cause pressed", async () => {
    api.getCase.mockResolvedValue(frustrationCase());
    api.getFrustratedSessions.mockResolvedValue({
      rows: [conversation("f-1", "Why do you keep asking me the same thing?")],
      total: 1,
      nextCursor: null,
    });
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    renderPage();
    await heading("Users on support-agent grew frustrated");

    expect(screen.getByText("The agent loops on refund questions")).toBeTruthy();
    expect(screen.getByText("What the agent did")).toBeTruthy();
    expect(screen.getByText("Why users got frustrated")).toBeTruthy();
    expect(screen.getByText("agent/prompt.md @ c0ffee1")).toBeTruthy();
    expect(screen.getByText("Always retry.")).toBeTruthy();
    expect(screen.getByText("Share of sessions with a user frustrated with the agent")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /^Show \d+ sessions?$/ }).map((b) => b.textContent)).toEqual([
      "Show 1 session",
    ]);
    const list = screen.getByRole("list", { name: "Frustrated sessions" });
    expect(within(list).getByText("This is the third time I asked.")).toBeTruthy();

    fireEvent.click(button("Show 1 session"));

    expect(await within(list).findByText("Why do you keep asking me the same thing?")).toBeTruthy();
    expect(api.getFrustratedSessions).toHaveBeenCalledWith("fnd-1", {
      limit: expect.any(Number),
      cursor: null,
      cause: { rcaReport: "rca-f", index: 0 },
    });
    const filter = screen.getByRole("group");
    expect(within(filter).getByRole("button", { name: "Cause 1 · 1" }).getAttribute("aria-pressed")).toBe("true");
    expect(within(filter).queryByRole("button", { name: /Cause 2/ })).toBeNull();
    expect(scrolled).toHaveBeenCalled();
  });

});

describe("a case with a classifier detail", () => {
  const ONSET = "2026-09-22T07:30:00Z";
  const DETAIL: ClassifierDetail = {
    rate: { ...GROUNDEDNESS_DETAIL.rate, onsetAt: ONSET },
    baselineFlagged: 21, flagLabel: "Yes", flagThreshold: 0.8, arlTarget: 50000, minDecisionInterval: null,
    learningUntil: null,
    words: {
      unitOne: "item", unitMany: "items", figureNote: "Share of items the check flagged",
      referenceLabel: "is the reference", referenceDetail: "Set from the first 1,000 items.",
      thresholdLine: "An item counts when its score is 0.80 or higher.",
    },
  };
  const OWNED_REPORT: RcaReport = {
    ...GROUNDEDNESS_REPORT,
    id: "rca-o",
    report_kind: "owned_causes",
    causes: [
      { ...GROUNDEDNESS_REPORT.causes[0], title: "The prompt stopped asking for a check", affected_count: 3, evidence_trace_ids: ["t-1", "t-2", "t-2"] },
      { ...GROUNDEDNESS_REPORT.causes[0], title: "A tool started returning empty pages", affected_count: 1, evidence_trace_ids: [] },
    ],
  };
  const classifierCase = (over: Partial<CaseDetail> = {}): CaseDetail => ({
    ...BASE, groundedness: null, classifier_detail: DETAIL, rca: null, rca_report_id: null, ...over,
    case: { ...BASE.case, detector: "item_check", title: "Flagged items on support-agent rose", rca_verdict: null },
  });

  // Bug: a detailed case drew no figure ("nothing here to plot"), or another story's words around its rate.
  it("draws What changed from the classifier detail and no Why block before RCA", async () => {
    api.getCase.mockResolvedValue(classifierCase());
    renderPage();
    await heading("Flagged items on support-agent rose");

    expect(screen.getByRole("heading", { name: "What changed" })).toBeTruthy();
    expect(screen.getByText("Share of items the check flagged")).toBeTruthy();
    expect(screen.getByText("6.40% of items are flagged")).toBeTruthy();
    expect(screen.getByText("2.10% is the reference")).toBeTruthy();
    expect(
      screen.getByText(
        "Set from the first 1,000 items. The current rate is 4.3 percentage points higher. An item counts when its score is 0.80 or higher.",
      ),
    ).toBeTruthy();
    expect(screen.queryByText(/nothing here to plot/)).toBeNull();
    expect(screen.queryByRole("heading", { name: "Why" })).toBeNull();
    expect(screen.getByText(new RegExp(`^${stamp(ONSET)}`))).toBeTruthy();
  });

  // Bug: an owned_causes report's causes offered no way to narrow the flagged list to the cause.
  it("offers a cause filter for an owned_causes report", async () => {
    api.getCase.mockResolvedValue(classifierCase({ rca: OWNED_REPORT, rca_report_id: "rca-o" }));
    renderPage();
    await heading("Flagged items on support-agent rose");

    expect(screen.getByText("The prompt stopped asking for a check")).toBeTruthy();
    expect(screen.getByText("3 flagged items")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /^Show \d+ items?$/ }).map((b) => b.textContent)).toEqual([
      "Show 2 items",
    ]);

    // Bug: Show N items scrolls nowhere, because the case page and the flagged list a build adds disagree on its id.
    const list = document.body.appendChild(Object.assign(document.createElement("div"), { id: FLAGGED_LIST_ID }));
    list.scrollIntoView = vi.fn();
    fireEvent.click(button("Show 2 items"));
    expect(list.scrollIntoView).toHaveBeenCalled();
    list.remove();
  });

  // Bug: a report of another kind on a detailed case offered a filter its causes cannot drive.
  it("offers no cause filter for a report of another kind", async () => {
    api.getCase.mockResolvedValue(
      classifierCase({ rca: { ...OWNED_REPORT, report_kind: "attribution" }, rca_report_id: "rca-o" }),
    );
    renderPage();
    await heading("Flagged items on support-agent rose");

    expect(screen.getByText("The prompt stopped asking for a check")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^Show \d+ items?$/ })).toBeNull();
  });
});

it("a case that cannot be read: says the case was not found, with the error", async () => {
  api.getCase.mockRejectedValue(new ApiError(404, { code: "CASE.NOT_FOUND", message: "no such case" }));
  renderPage();

  expect(await heading("Case not found")).toBeTruthy();
  expect(screen.getByText("CASE.NOT_FOUND")).toBeTruthy();
});

describe("CasePage, groundedness", () => {
  /** One answer per cause, each unlike the unfiltered first page's, so the list shows which filter it read. */
  const causeAnswer = (traceId: string, text: string): FlaggedAnswer => ({
    ...FLAGGED_ANSWER,
    traceId,
    spanId: `span-${traceId}`,
    answer: text,
    flaggedSentences: [{ start: 0, end: text.length, score: 0.99 }],
  });
  const CAUSE_ANSWERS = [
    causeAnswer("t-1", "The old policy page says refunds run for 60 days."),
    causeAnswer("t-4", "The API allows 1,000 requests per minute per key."),
  ];
  const TITLE_1 = "The retriever still serves the old pricing and policy pages";
  const FIX_1 = "Remove the old folders from the index sources and rebuild the index.";
  const TITLE_2 = "The system prompt asks for a complete answer every time";
  const FIX_2 = "Tell the agent to say when the documents don't answer the question.";
  const NO_REPO =
    "These causes aren't linked to a prompt or code because no repository was connected. Connect a repository and run RCA again to find them.";

  /** Waits for `read`'s answer to land, so what the page leaves out after it is settled rather than pending. */
  async function settled(read: Mock) {
    await waitFor(() => expect(read).toHaveBeenCalled());
    await act(async () => {
      await read.mock.results.at(-1)?.value;
    });
  }

  beforeEach(() => {
    api.getGitIntegration.mockResolvedValue(null);
    api.getFlaggedAnswers.mockImplementation(async (_id: string, params?: { cause?: { index: number } }) => ({
      rows: [params?.cause ? CAUSE_ANSWERS[params.cause.index] : FLAGGED_ANSWER],
      total: 1,
      nextCursor: null,
    }));
  });

  it("cards the proven cause, draws the rate, and filters the answers to the cause pressed", async () => {
    renderPage();

    await screen.findByRole("heading", { name: "Answers on support-agent became less grounded" });
    expect(screen.getByText(TITLE_1)).toBeTruthy();
    expect(screen.getByText(FIX_1)).toBeTruthy();
    expect(screen.getByText("Why answers went unsupported")).toBeTruthy();
    expect(screen.getByText("What to do")).toBeTruthy();
    expect(screen.getByText("support-agent/retrieval/index.yaml @ a41c0de")).toBeTruthy();
    // The second cause is a lead, and a lead is not shown beside a proven cause.
    expect(screen.queryByText(TITLE_2)).toBeNull();
    expect(screen.queryByText(FIX_2)).toBeNull();
    expect(screen.getAllByRole("button", { name: /^Show \d+ answers$/ }).map((b) => b.textContent)).toEqual([
      "Show 3 answers",
    ]);
    expect(screen.queryByText(NO_REPO)).toBeNull();

    expect(screen.getByText("6.4% of answers flagged")).toBeTruthy();
    expect(screen.getByText(/This rate opened this case\./)).toBeTruthy();

    const filter = screen.getByRole("group", { name: "Filter answers" });
    expect(within(filter).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "All · 58",
      "Cause 1 · 3",
      "Cause 2 · 2",
    ]);
    const answers = screen.getByRole("list", { name: "Flagged answers" });
    within(answers).getByRole("button", { name: /Refunds are available for up to 60 days/ });

    fireEvent.click(screen.getByRole("button", { name: "Show 3 answers" }));

    await within(answers).findByRole("button", { name: /The old policy page/ });
    expect(within(answers).queryByRole("button", { name: /Refunds are available for up to 60 days/ })).toBeNull();
    expect(within(answers).queryByRole("button", { name: /The API allows 1,000 requests/ })).toBeNull();
    expect(within(filter).getByRole("button", { name: "Cause 1 · 3" }).getAttribute("aria-pressed")).toBe("true");

    expect(screen.getByRole("button", { name: "Re-run RCA" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Close" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Absorb" })).toBeTruthy();
  });

  it("offers an owner Connect repository while the project has none, and drops it once one is connected", async () => {
    renderPage();
    await screen.findByRole("button", { name: "Re-run RCA" });
    await screen.findByRole("button", { name: "Connect repository" });
    cleanup();

    api.getGitIntegration.mockClear();
    api.getGitIntegration.mockResolvedValue({ provider: "github", repoOwner: "acme", repoName: "agent" });
    renderPage();
    await screen.findByRole("button", { name: "Re-run RCA" });
    await settled(api.getGitIntegration);
    expect(screen.queryByRole("button", { name: "Connect repository" })).toBeNull();
  });

  it("hides the RCA controls when the organization does not have RCA", async () => {
    auth.getCapabilities.mockResolvedValue({ capabilities: { rca_enabled: false } as CapabilitiesView["capabilities"] });
    renderPage();

    // The finished report still reads: only running it again is withheld.
    await screen.findByText(TITLE_1);
    await settled(auth.getCapabilities);
    expect(screen.queryByRole("button", { name: "Re-run RCA" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Connect repository" })).toBeNull();
  });

  it("says the causes are not tied to code when the run had no repository", async () => {
    api.getCase.mockResolvedValue({ ...BASE, rca: { ...GROUNDEDNESS_REPORT, repo_available: false } });
    renderPage();

    await screen.findByText(NO_REPO);
    expect(screen.queryByText(/support-agent\/retrieval\/index\.yaml/)).toBeNull();
  });
});
