// SPDX-License-Identifier: Apache-2.0
/*
 * CasePage beyond the groundedness story: the verbs that close a case, the RCA run and its in-flight
 * and failed states, the answer and the working behind it, the failures a page at a time, and a
 * frustration case's causes. The bugs worth catching: a verb sent for the wrong case or offered before
 * there is a report to justify it, a run that can be pressed twice, a failed run that reads as an
 * empty one, and a cause's "Show" that filters nothing.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type {
  CaseDetail,
  EvidenceSpan,
  FrustratedConversation,
  FrustrationDetail,
  RcaReport,
} from "../../api/types";
import { ApiError } from "../../api/types";
import type { CapabilitiesView, Me } from "../../api/types-auth";
import { AuthProvider } from "../../auth/AuthContext";
import { ToastProvider } from "../../ui";
import { CasePage } from "./CasePage";
import { GROUNDEDNESS_CASE_DETAIL, GROUNDEDNESS_DETAIL, GROUNDEDNESS_REPORT } from "../../test/groundednessFixtures";

const OWNER: Me = {
  id: "user-1",
  email: "owner@example.com",
  orgs: [{ id: "org-1", slug: "acme", name: "Acme", role: "owner" }],
  platform_staff: false,
};

const { me, getCapabilities } = vi.hoisted(() => ({
  me: vi.fn<() => Promise<Me>>(),
  getCapabilities: vi.fn<() => Promise<CapabilitiesView>>(),
}));

vi.mock("../../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/client")>();
  return { ...actual, auth: { ...actual.auth, me, getCapabilities } };
});

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getCase: vi.fn(),
  getRcaReport: vi.fn(),
  getFlaggedAnswers: vi.fn(),
  getFrustratedSessions: vi.fn(),
  getBehaviorFindingEvidence: vi.fn(),
  getGitIntegration: vi.fn(),
  getTrace: vi.fn(),
  resolveCase: vi.fn(),
  muteCase: vi.fn(),
  unmuteCase: vi.fn(),
  absorbCase: vi.fn(),
  runCaseRca: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
    useProjectApi: () => api,
  };
});

// ---- fixtures -------------------------------------------------------------------------------

const BASE = GROUNDEDNESS_CASE_DETAIL;

/** A case whose classifier draws no figure and ranks no causes: the answer-and-working layout. */
function plainCase(over: Partial<CaseDetail> = {}, caseOver: Partial<CaseDetail["case"]> = {}): CaseDetail {
  return {
    ...BASE,
    groundedness: null,
    rca: null,
    rca_report_id: null,
    ...over,
    case: { ...BASE.case, detector: "loop", rca_verdict: null, ...caseOver },
  };
}

const ANSWER_REPORT: RcaReport = {
  ...GROUNDEDNESS_REPORT,
  report_kind: "attribution",
  verdict: "model_change",
  causes: [],
  hypotheses: [
    {
      title: "The serving model changed under the call site",
      confidence: "high",
      rationale: "The failures start at the model rollout.",
      evidence_trace_ids: ["trace-0123456789"],
    },
    { title: "Traffic moved to longer inputs", confidence: "low", rationale: "Inputs grew.", evidence_trace_ids: [] },
  ],
  ruled_out: [
    { check: "model_swap", assessment: "explains", detail: "Model id changed.", measurement: null, passed: false },
    { check: "input_length", assessment: "contributing", detail: "Longer inputs.", measurement: null, passed: false },
    { check: "grader_drift", assessment: "ruled_out", detail: "Grader unchanged.", measurement: null, passed: true },
    { check: "traffic_mix", assessment: "unknown", detail: "Not measured.", measurement: null, passed: false },
  ] as RcaReport["ruled_out"],
};

const span = (i: number, over: Partial<EvidenceSpan> = {}) =>
  ({
    traceId: `trace-${i}`,
    spanId: `span-${i}`,
    startedAt: "2026-09-23T14:00:00Z",
    inputPreview: `input ${i}`,
    outputPreview: null,
    errorType: `Timeout${i}`,
    callSiteId: "support-agent",
    latencyMs: 1200,
    ...over,
  }) as EvidenceSpan;

const conversation = (id: string, message: string): FrustratedConversation =>
  ({
    traceId: id,
    sessionId: `sess-${id}`,
    conversationId: `conv-${id}`,
    callSiteId: "support-agent",
    contextTraceIds: [],
    flaggedAt: "2026-09-23T14:00:00Z",
    score: 0.9,
    message,
    cleared: false,
  }) as FrustratedConversation;

const FRUSTRATION: FrustrationDetail = {
  rate: { ...GROUNDEDNESS_DETAIL.rate, failuresCur: 40 },
  arlTarget: 50000,
  baselineFrustrated: 10,
  conversations: [conversation("f-all", "This is the third time I asked.")],
  conversationsNextCursor: null,
  jevThreshold: 0.5,
  minDecisionInterval: 3600,
  scorerVersion: "v1",
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
  ...BASE,
  groundedness: null,
  frustration: FRUSTRATION,
  rca: FRUSTRATION_REPORT,
  rca_report_id: "rca-f",
  ...over,
  case: { ...BASE.case, detector: "frustration", title: "Users on support-agent grew frustrated" },
});

// ---- harness --------------------------------------------------------------------------------

beforeEach(() => {
  me.mockResolvedValue(OWNER);
  getCapabilities.mockResolvedValue({ capabilities: { rca_enabled: true } as CapabilitiesView["capabilities"] });
  api.getCase.mockResolvedValue(BASE);
  api.getGitIntegration.mockResolvedValue({ provider: "github" });
  api.getFlaggedAnswers.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getFrustratedSessions.mockResolvedValue({ rows: [], total: 0, nextCursor: null });
  api.getBehaviorFindingEvidence.mockResolvedValue({ rows: [], nextCursor: null, counts: {}, recordedCounts: {} });
  api.getTrace.mockReturnValue(new Promise(() => {}));
  for (const f of [api.resolveCase, api.muteCase, api.unmuteCase, api.absorbCase, api.runCaseRca]) f.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={["/orgs/acme/projects/default/cases/case-1"]}>
            <Routes>
              <Route path="/orgs/:orgSlug/projects/:projectSlug/cases/:caseId" element={<CasePage />} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const heading = (name: string) => screen.findByRole("heading", { level: 1, name });
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const dialog = () => screen.getByRole("dialog");

// ---- tests ----------------------------------------------------------------------------------

describe("closing a case", () => {
  it("resolves the case with the reason given, then closes the dialog", async () => {
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Resolve case"));
    fireEvent.change(within(dialog()).getByLabelText("Reason"), { target: { value: "prompt fixed" } });
    fireEvent.click(within(dialog()).getByRole("button", { name: "Resolve case" }));

    await waitFor(() => expect(api.resolveCase).toHaveBeenCalledWith("case-1", "prompt fixed", "fixed"));
    await waitFor(() => expect(screen.queryByLabelText("Reason")).toBeNull());
    expect(api.getCase).toHaveBeenCalledTimes(2);
  });

  it("cancels a resolve without sending it", async () => {
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Resolve case"));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByLabelText("Reason")).toBeNull();
    fireEvent.click(button("Resolve case"));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Close" }));
    expect(screen.queryByLabelText("Reason")).toBeNull();

    await settle();
    expect(api.resolveCase).not.toHaveBeenCalled();
  });

  it("absorbs the case as legitimate, and says why an absorb was refused", async () => {
    api.absorbCase.mockRejectedValueOnce(new ApiError(409, { code: "CASE.LOCKED", message: "Case is locked" }));
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Absorb as legitimate"));
    const confirmAbsorb = () => within(dialog()).getByRole("button", { name: "Absorb as legitimate" });
    fireEvent.click(confirmAbsorb());
    expect(await within(dialog()).findByText("CASE.LOCKED")).toBeTruthy();

    fireEvent.click(confirmAbsorb());
    await waitFor(() => expect(api.absorbCase).toHaveBeenCalledTimes(2));
    expect(api.absorbCase).toHaveBeenLastCalledWith("case-1");
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("closes the absorb dialog on Cancel without absorbing", async () => {
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Absorb as legitimate"));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(button("Absorb as legitimate"));
    fireEvent.click(within(dialog()).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();

    await settle();
    expect(api.absorbCase).not.toHaveBeenCalled();
  });

  it("mutes an open case and unmutes a muted one, and says why a mute failed", async () => {
    api.muteCase.mockRejectedValue(new ApiError(500, { code: "CASE.FAILED", message: "no" }));
    renderPage();
    await heading(BASE.case.title);

    fireEvent.click(button("Mute case"));
    await waitFor(() => expect(api.muteCase).toHaveBeenCalledWith("case-1"));
    expect(await screen.findByText("CASE.FAILED")).toBeTruthy();
    cleanup();

    api.getCase.mockResolvedValue({ ...BASE, case: { ...BASE.case, state: "muted" } });
    renderPage();
    await heading(BASE.case.title);
    fireEvent.click(button("Unmute case"));
    await waitFor(() => expect(api.unmuteCase).toHaveBeenCalledWith("case-1"));
  });

  it("names the plain verbs on a case with no ranked causes, and offers absorb only where it is available", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1", absorb_available: false }));
    renderPage();
    await heading(BASE.case.title);

    expect(button("Resolve")).toBeTruthy();
    expect(button("Mute")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Absorb as legitimate" })).toBeNull();
    expect(screen.getByText("Close this case")).toBeTruthy();
  });

  it("offers no verbs on a resolved case, and says who resolved it and why", async () => {
    api.getCase.mockResolvedValue({
      ...BASE,
      case: {
        ...BASE.case,
        state: "resolved",
        resolved_at: "2026-09-23T15:00:00Z",
        resolved_by: "dana@example.com",
        resolution_reason: "prompt fixed",
        disposition: "fixed",
      },
    });
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(/by dana@example.com: prompt fixed/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Resolve|Mute|Re-run RCA/ })).toBeNull();
  });

  it("makes a case read-only once its classifier is gone, and says why", async () => {
    api.getCase.mockResolvedValue({ ...BASE, detector_available: false });
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(/is no longer available to this organization, so this case is read-only/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Resolve|Mute|Re-run RCA/ })).toBeNull();
  });
});

describe("the meta line", () => {
  it("names the classifier as the classifiers page does, and no call site for the unattributed sentinel", async () => {
    api.getCase.mockResolvedValue(plainCase({}, { detector: "tool_error", call_site_id: "__unattributed__" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("Tool error")).toBeTruthy();
    expect(screen.queryByText("__unattributed__")).toBeNull();
  });
});

describe("running RCA", () => {
  it("runs RCA for this case, and withholds the closing verbs until there is a report", async () => {
    api.getCase.mockResolvedValue(plainCase());
    api.runCaseRca.mockReturnValue(new Promise(() => {}));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByRole("button", { name: "Resolve" })).toBeNull();
    expect(button("Mute")).toBeTruthy();
    expect(screen.queryByText("Run RCA before resolving this case.")).toBeNull();
    fireEvent.click(button("Run RCA"));

    await waitFor(() => expect(api.runCaseRca).toHaveBeenCalledWith("case-1"));
    expect(button("Analyzing…").disabled).toBe(true);
    expect(screen.getByText("Reading the evidence and bracketing the change point.")).toBeTruthy();
  });

  it("asks a ranked case to run RCA before resolving it", async () => {
    api.getCase.mockResolvedValue({ ...BASE, rca: null, rca_report_id: null });
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("Run RCA before resolving this case.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Resolve case" })).toBeNull();
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
        .mockResolvedValueOnce({ ...ANSWER_REPORT, status: "pending" })
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

describe("the answer and the working behind it", () => {
  it("cards the leading hypothesis, then counts and lists every check and the weaker leads", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: ANSWER_REPORT, rca_report_id: "rca-1" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("Serving model changed")).toBeTruthy();
    expect(screen.getByText("The failures start at the model rollout.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "trace-01…" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/trace-0123456789",
    );
    expect(screen.getByText("4 explanations tested, 1 eliminated.")).toBeTruthy();
    const assessments = ["explains", "contributing", "ruled out", "unknown"].map(
      (a) => (screen.getByText(a) as HTMLElement).style.color,
    );
    expect(assessments).toEqual(["var(--color-error)", "var(--color-warning)", "var(--color-subtle)", "var(--color-muted)"]);
    expect(screen.getByText("Traffic moved to longer inputs").closest("summary")).toBeTruthy();
  });

  it("shows the summary when the run reached no hypothesis, and draws no working block", async () => {
    api.getCase.mockResolvedValue(
      plainCase({
        rca: { ...ANSWER_REPORT, hypotheses: [], ruled_out: [], verdict: "inconclusive", summary: "Nothing moved." },
        rca_report_id: "rca-1",
      }),
    );
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("Nothing moved.")).toBeTruthy();
    expect(screen.getByText("Inconclusive")).toBeTruthy();
    expect(screen.queryByText("What else was checked")).toBeNull();
  });

  it("says nothing when the run reached no hypothesis and wrote no summary", async () => {
    api.getCase.mockResolvedValue(
      plainCase({ rca: { ...ANSWER_REPORT, hypotheses: [], ruled_out: [], summary: null }, rca_report_id: "rca-1" }),
    );
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByText("Why")).toBeNull();
  });

  it("says a failed run did not finish, rather than showing an empty answer", async () => {
    api.getCase.mockResolvedValue(plainCase({ rca: { ...ANSWER_REPORT, status: "failed" }, rca_report_id: "rca-1" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText(/The analysis did not finish/)).toBeTruthy();
    expect(screen.queryByText("The serving model changed under the call site")).toBeNull();
  });

  it("puts the cause on its own line, hedged when the verdict is", async () => {
    api.getCase.mockResolvedValue(plainCase({}, { rca_verdict: "inconclusive", cause: "a model rollout" }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.getByText("a model rollout").textContent).toBe("Likely: a model rollout");
  });
});

describe("the failures", () => {
  it("lists the failing calls a page at a time, each opening its trace", async () => {
    api.getCase.mockResolvedValue(plainCase());
    api.getBehaviorFindingEvidence
      .mockResolvedValueOnce({ rows: [span(1), span(2, { traceId: null })], nextCursor: "c2", recordedCounts: { witness: 3 } })
      .mockResolvedValueOnce({ rows: [span(3, { spanId: null, startedAt: null })], nextCursor: null, recordedCounts: {} });
    renderPage();

    expect(await screen.findByText("3 in this window")).toBeTruthy();
    expect(screen.getByText("Timeout1").closest("a")!.getAttribute("href")).toBe(
      "/orgs/acme/projects/default/traces/trace-1#span-1",
    );
    expect(screen.getByText("Timeout2").closest("a")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Show more" }));

    const third = await screen.findByText("Timeout3");
    expect(screen.getByText("Timeout1")).toBeTruthy();
    expect(third.closest("a")!.getAttribute("href")).toBe("/orgs/acme/projects/default/traces/trace-3");
    expect(api.getBehaviorFindingEvidence).toHaveBeenLastCalledWith("fnd-1", { role: "witness", limit: 8, cursor: "c2" });
    expect(screen.queryByRole("button", { name: "Show more" })).toBeNull();
  });

  it("says the calls aged out when none are stored", async () => {
    api.getCase.mockResolvedValue(plainCase());
    renderPage();

    expect(await screen.findByText(/have aged out of retention/)).toBeTruthy();
  });

  it("draws no failures block for a case with no finding", async () => {
    api.getCase.mockResolvedValue(plainCase({ latest_finding_id: null }));
    renderPage();
    await heading(BASE.case.title);

    expect(screen.queryByText("The failures")).toBeNull();
    expect(api.getBehaviorFindingEvidence).not.toHaveBeenCalled();
  });
});

describe("the activity", () => {
  it("stays collapsed until asked, then lists each event and who did it", async () => {
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
});

describe("a frustration case", () => {
  it("ranks its causes by session, shows where in the repo, and filters the sessions to the cause pressed", async () => {
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

    expect(
      screen.getByText("Tessary identified 2 likely causes from the 40 frustrated sessions and the agent's repository."),
    ).toBeTruthy();
    expect(screen.getByText("agent/prompt.md")).toBeTruthy();
    expect(screen.getByText("Always retry.")).toBeTruthy();
    expect(screen.getByText("c0ffee12…")).toBeTruthy();
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

  it("falls back to the summary when the run named no cause", async () => {
    api.getCase.mockResolvedValue(
      frustrationCase({ rca: { ...FRUSTRATION_REPORT, causes: [], summary: "No shared cause.", verdict: "no_cause_found" } }),
    );
    renderPage();
    await heading("Users on support-agent grew frustrated");

    expect(screen.getByText("No shared cause.")).toBeTruthy();
    expect(screen.getByText("No cause found")).toBeTruthy();
  });

  it("draws no cause card for a summary-less run with no causes", async () => {
    api.getCase.mockResolvedValue(frustrationCase({ rca: { ...FRUSTRATION_REPORT, causes: [], summary: null } }));
    renderPage();
    await heading("Users on support-agent grew frustrated");

    expect(screen.queryByText("Likely cause")).toBeNull();
  });
});

describe("a case that cannot be read", () => {
  it("says the case was not found, with the error", async () => {
    api.getCase.mockRejectedValue(new ApiError(404, { code: "CASE.NOT_FOUND", message: "no such case" }));
    renderPage();

    expect(await heading("Case not found")).toBeTruthy();
    expect(screen.getByText("CASE.NOT_FOUND")).toBeTruthy();
  });
});
