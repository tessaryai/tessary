// SPDX-License-Identifier: Apache-2.0
/*
 * RcaReport's causes: on an older report, proven ones first on the case page's card and leads last under their
 * own heading; on a current one, every cause together with its confidence. A groundedness cause counts flagged
 * answers and links its traces; a frustration cause counts sessions and links the sessions and their turns.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { renderRoute } from "../test/render";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { RcaReport as RcaReportData } from "../api/types";
import type { Me } from "../api/types-auth";
import { AuthProvider } from "../auth/AuthContext";
import { RcaReport } from "./RcaReport";
import { CURRENT_GROUNDEDNESS_REPORT, GROUNDEDNESS_REPORT } from "../test/groundednessFixtures";

const { me } = vi.hoisted(() => ({ me: vi.fn<() => Promise<Me>>() }));

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, auth: { ...actual.auth, me } };
});

let report: RcaReportData = GROUNDEDNESS_REPORT;
const api = {
  base: "/api/orgs/acme/projects/default",
  getRcaReport: vi.fn(async (_id: string) => report),
  getGitIntegration: vi.fn(async () => null),
  rerunRca: vi.fn(),
};

vi.mock("../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
    useProjectApi: () => api,
  };
});

afterEach(() => {
  report = GROUNDEDNESS_REPORT;
  api.rerunRca.mockReset();
});

function renderReport(role: "owner" | "member" = "member") {
  me.mockResolvedValue({
    id: "user-1",
    email: "a@example.com",
    orgs: [{ id: "org-1", slug: "acme", name: "Acme", role }],
    platform_staff: false,
  });
  renderRoute(
    <AuthProvider>
      <RcaReport />
    </AuthProvider>,
    { route: "/orgs/acme/projects/default/rca/rca-1", path: "/orgs/:orgSlug/projects/:projectSlug/rca/:reportId" },
  );
}

/** Each evidence link's label and where it goes, in page order. */
function evidenceLinks() {
  return screen
    .getAllByRole("link")
    .map((a) => [a.textContent, a.getAttribute("href")])
    .filter(([, href]) => /\/(traces|sessions)\//.test(href ?? ""));
}

const TRACES = "/orgs/acme/projects/default/traces/";

describe("RcaReport", () => {
  it("counts a groundedness report's causes in flagged answers and links the traces", async () => {
    renderReport();

    await screen.findByText("The retriever still serves the old pricing and policy pages");
    screen.getByText(/^Call site support-agent · Flagged answers rose to 6\.4% from a learned 2\.1% · .+ onward$/);
    expect(screen.getByText("3 flagged answers")).toBeTruthy();
    expect(screen.getByText("2 flagged answers")).toBeTruthy();
    expect(screen.queryByText(/\d+ sessions?$/)).toBeNull();
    expect(evidenceLinks()).toEqual([
      ["t-1", `${TRACES}t-1`],
      ["t-2", `${TRACES}t-2`],
      ["t-3", `${TRACES}t-3`],
      ["t-4", `${TRACES}t-4`],
      ["t-5", `${TRACES}t-5`],
    ]);
  });

  /** Catches a lead read as the answer: it sits under its own heading, below the write-up, labelled as a lead. */
  it("puts the proven cause first and the lead under Leads not proven", async () => {
    renderReport();

    const proven = await screen.findByText("The retriever still serves the old pricing and policy pages");
    const leadsHeading = screen.getByText("Leads not proven");
    const lead = screen.getByText("The system prompt asks for a complete answer every time");
    expect(proven.compareDocumentPosition(leadsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(leadsHeading.compareDocumentPosition(lead) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("Why answers went unsupported")).toBeTruthy();
    expect(screen.getByText("Why it might be")).toBeTruthy();
    expect(screen.getByText("To confirm")).toBeTruthy();
  });

  /** Catches an older report's checklist losing its ids or reasoning once current reports drop them. */
  it("shows an older report's checklist with each check's id and reasoning", async () => {
    report = {
      ...GROUNDEDNESS_REPORT,
      ruled_out: [
        {
          check: "model_swap",
          question: "Did the serving model change?",
          assessment: "explains",
          detail: "Model id changed.",
          measurement: null,
          passed: false,
        },
        { check: "grader_drift", question: null, assessment: "ruled_out", detail: "Grader unchanged.", measurement: null, passed: true },
      ],
    };
    renderReport();

    expect(await screen.findByText("Checklist")).toBeTruthy();
    expect(screen.getByText("Did the serving model change?")).toBeTruthy();
    expect(screen.getByText("model_swap")).toBeTruthy();
    expect(screen.getByText("Model id changed.")).toBeTruthy();
    expect(screen.getByText("grader_drift")).toBeTruthy();
    expect(screen.getByLabelText("Explains the movement")).toBeTruthy();
  });

  /** Catches a current medium cause sent to the leads, and an internal id shown beside a ruled-out sentence. */
  it("lists a current report's high and medium causes together, each with its confidence, and what was ruled out", async () => {
    report = CURRENT_GROUNDEDNESS_REPORT;
    renderReport();

    const high = await screen.findByText("The retriever still serves the old pricing and policy pages");
    const medium = screen.getByText("The system prompt asks for a complete answer every time");
    const ruledOut = screen.getByText("What else was checked");
    expect(screen.getByText("Causes")).toBeTruthy();
    expect(screen.queryByText("Leads not proven")).toBeNull();
    expect(high.compareDocumentPosition(medium) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(medium.compareDocumentPosition(ruledOut) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("High")).toBeTruthy();
    expect(screen.getByText("Medium")).toBeTruthy();
    expect(screen.getByText("What changed")).toBeTruthy();
    expect(screen.getByText("What the agent does")).toBeTruthy();
    expect(screen.getAllByText("Why answers went unsupported")).toHaveLength(2);
    expect(screen.queryByText("Why it might be")).toBeNull();

    expect(screen.queryByText("Checklist")).toBeNull();
    expect(screen.getByText("Question traffic stayed on the same topics before and after the onset.")).toBeTruthy();
    expect(screen.getAllByLabelText("Ruled out")).toHaveLength(2);
    expect(screen.queryByText("ruled_out_1")).toBeNull();
  });

  it("counts a frustration report's causes in sessions and links the sessions and their turns", async () => {
    report = {
      ...GROUNDEDNESS_REPORT,
      report_kind: "frustration_causes",
      metric: "frustration",
      current_value: 0.052,
      prior_value: 0.018,
      causes: [
        {
          title: "The agent repeats the same clarifying question",
          confidence: "high",
          change: null,
          type: null,
          what_changed: "Asked for the order number after the user gave it.",
          how_it_caused_this: "Users had to repeat themselves.",
          next_step: "Read the order number from the conversation before asking.",
          attribution: null,
          evidence_session_ids: ["session-0000aa01", "session-0000aa02"],
          evidence_trace_ids: ["trace-0000bb01"],
          affected_count: 4,
        },
      ],
    };
    renderReport();

    await screen.findByText("The agent repeats the same clarifying question");
    screen.getByText(/^Call site support-agent · Frustrated sessions rose to 5\.2% from a learned 1\.8% · .+ onward$/);
    expect(screen.getByText("4 frustrated sessions")).toBeTruthy();
    expect(screen.getByText("Why users got frustrated")).toBeTruthy();
    expect(evidenceLinks()).toEqual([
      ["session-…", "/orgs/acme/projects/default/sessions/session-0000aa01"],
      ["session-…", "/orgs/acme/projects/default/sessions/session-0000aa02"],
      ["trace-00…", `${TRACES}trace-0000bb01`],
    ]);
  });

  it("places a cause in the repository by kind, path, and commit", async () => {
    report = {
      ...GROUNDEDNESS_REPORT,
      causes: [
        {
          ...GROUNDEDNESS_REPORT.causes[0],
          attribution: { kind: "prompt", path: "agent/system.md", commit: "c0ffee1234abcd", excerpt: null },
        },
        {
          ...GROUNDEDNESS_REPORT.causes[1],
          attribution: { kind: "code", path: "rag/retrieve.py", commit: null, excerpt: null },
        },
      ],
    };
    renderReport();

    expect(await screen.findByText("agent/system.md @ c0ffee1")).toBeTruthy();
    expect(screen.getByText("rag/retrieve.py")).toBeTruthy();
    expect(screen.getByText("Prompt")).toBeTruthy();
    expect(screen.getByText("Code")).toBeTruthy();
  });

  it("re-runs the analysis as a new report and opens it", async () => {
    api.rerunRca.mockResolvedValue({ ...GROUNDEDNESS_REPORT, id: "rca-2" });
    renderReport();

    fireEvent.click(await screen.findByRole("button", { name: "Re-run RCA" }));

    await waitFor(() => expect(api.getRcaReport).toHaveBeenLastCalledWith("rca-2"));
    expect(api.rerunRca).toHaveBeenCalledWith("rca-1");
  });

  it("names why a re-run was refused, without the error code", async () => {
    api.rerunRca.mockRejectedValue(new Error("RCA.BUSY: an analysis is already running"));
    renderReport();

    fireEvent.click(await screen.findByRole("button", { name: "Re-run RCA" }));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Re-run RCA" }).getAttribute("title")).toBe(
        "an analysis is already running",
      ),
    );
  });

  it("offers an owner Connect repository on a report analyzed without one", async () => {
    report = { ...GROUNDEDNESS_REPORT, repo_available: false };
    renderReport("owner");

    fireEvent.click(await screen.findByRole("button", { name: "Connect repository" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getAllByRole("button", { name: "Close" }).at(-1)!);
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });
});
