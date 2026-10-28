// SPDX-License-Identifier: Apache-2.0
/*
 * RcaReport's causes: on an older report, proven ones first on the case page's card and leads last under their
 * own heading; on a current one, every cause together with its confidence. A groundedness cause counts flagged
 * answers and links its traces; a frustration cause counts sessions and links the sessions and their turns.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderRoute } from "../test/render";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { RcaReport as RcaReportData } from "../api/types";
import { AuthProvider } from "../auth/AuthContext";
import { RcaReport } from "./RcaReport";
import { CURRENT_GROUNDEDNESS_REPORT, GROUNDEDNESS_REPORT } from "../test/groundednessFixtures";
import { api, auth } from "../test/api";

vi.mock("../api/client", () => import("../test/api"));
vi.mock("../tenant/TenantContext", () => import("../test/api"));

let report: RcaReportData = GROUNDEDNESS_REPORT;
beforeEach(() => {
  report = GROUNDEDNESS_REPORT;
  api.getRcaReport.mockImplementation(async () => report);
  api.getGitIntegration.mockResolvedValue(null);
});

function renderReport(role: "owner" | "member" = "member") {
  auth.me.mockResolvedValue({
    id: "user-1", email: "a@example.com", orgs: [{ id: "org-1", slug: "acme", name: "Acme", role }],
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
          check: "model_swap", question: "Did the serving model change?", assessment: "explains",
          detail: "Model id changed.", measurement: null, passed: false,
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
      ...GROUNDEDNESS_REPORT, report_kind: "frustration_causes", metric: "frustration", current_value: 0.052,
      prior_value: 0.018,
      causes: [
        {
          title: "The agent repeats the same clarifying question", confidence: "high", change: null, type: null,
          what_changed: "Asked for the order number after the user gave it.",
          how_it_caused_this: "Users had to repeat themselves.",
          next_step: "Read the order number from the conversation before asking.", attribution: null,
          evidence_session_ids: ["session-0000aa01", "session-0000aa02"],
          evidence_trace_ids: ["trace-0000bb01"],
          affected_count: 4,
          affected_of: null,
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

  // Bug: a cause's place labelled with another kind's name, or a kind this page does not know given one.
  it("places a cause in the repository by kind, path, and commit", async () => {
    report = {
      ...GROUNDEDNESS_REPORT,
      causes: [
        {
          ...GROUNDEDNESS_REPORT.causes[0],
          attribution: { kind: "prompt", path: "agent/system.md", commit: "c0ffee1234abcd", excerpt: null },
        },
        ...["code", "model", "tool", "data", "deploy", "config", "infra", "traffic", "upstream", "a_kind_from_later"].map((kind) => ({
          ...GROUNDEDNESS_REPORT.causes[0],
          attribution: { kind, path: `${kind}.py`, commit: null, excerpt: null },
        })),
      ],
    };
    renderReport();

    expect(await screen.findByText("agent/system.md @ c0ffee1")).toBeTruthy();
    expect(screen.getByText("code.py")).toBeTruthy();
    expect(
      ["Prompt", "Code", "Model", "Tool", "Data", "Deploy", "Config", "Infrastructure", "Traffic", "Upstream", "Where"].map(
        (label) => screen.getAllByText(label).length,
      ),
    ).toEqual([1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]);
  });

  // Bug: the flagged total never shown beside a cause's count, or an older cause without one reading "of null".
  it("shows how many of the flagged rows each cause explains", async () => {
    report = {
      ...CURRENT_GROUNDEDNESS_REPORT,
      causes: [
        { ...CURRENT_GROUNDEDNESS_REPORT.causes[0], affected_count: 3, affected_of: 58 },
        { ...CURRENT_GROUNDEDNESS_REPORT.causes[1], affected_of: null },
      ],
    };
    renderReport();

    expect(await screen.findByText("3 of 58 flagged answers")).toBeTruthy();
    expect(screen.getByText("2 flagged answers")).toBeTruthy();
  });

  // Bug: what the analysis read shown nowhere, or a line shown on an older report that recorded none.
  it("shows what the analysis read under the verdict", async () => {
    report = {
      ...CURRENT_GROUNDEDNESS_REPORT,
      coverage: {
        read: [{ role: "witness", count: 58 }, { role: "baseline", count: 200 }],
        how_chosen: "every witness row, baseline paged whole",
      },
    };
    renderReport();

    // The label's row, then the card body that holds the label row and the summary.
    const verdict = (await screen.findByText("Verdict")).parentElement!.parentElement!;
    expect(
      within(verdict).getByText("Read 58 witness rows and 200 baseline rows · every witness row, baseline paged whole"),
    ).toBeTruthy();
  });

  it("shows no coverage line on a report that recorded none", async () => {
    report = { ...CURRENT_GROUNDEDNESS_REPORT, coverage: null };
    renderReport();

    await screen.findByText("Verdict");
    expect(screen.queryByText(/^Read /)).toBeNull();
  });

  // Bug: a drift report's mean of 2500 ms ran through the rate formatter and read as 250000%.
  it("heads a drift report with the mean then and now in the measure's units", async () => {
    report = {
      ...GROUNDEDNESS_REPORT, report_kind: "metric_movement", metric: "duration_drift", subject_kind: "metric_baseline",
      current_value: 2500, prior_value: 1000, delta: 1500,
    };
    renderReport();

    expect(await screen.findByText(/^metric_baseline · Duration rose to 2\.5s from 1s \(\+1\.5s\) · .+ onward$/)).toBeTruthy();
  });

  // Bug: the header said "vs the 24h before" when the window started at the reference, days earlier.
  it("names a movement report's reference window rather than assuming a day", async () => {
    report = {
      ...GROUNDEDNESS_REPORT, report_kind: "metric_movement", metric: "tool_error", subject_kind: "classifier",
      current_value: 0, prior_value: 0, delta: 0,
    };
    renderReport();

    const subtitle = await screen.findByText(/^classifier · /);
    expect(subtitle.textContent).toMatch(/ onward vs .+ to .+$/);
    expect(subtitle.textContent).not.toContain("24h");
  });

  // Bug: a report on a measure with no cause words of its own borrows another kind's, so a standing cause reads
  // "Why it fails more".
  it("asks why it happens for a standing cause on a measure with no words of its own", async () => {
    report = { ...CURRENT_GROUNDEDNESS_REPORT, report_kind: "owned_causes", metric: "item_check" };
    renderReport();

    expect(await screen.findByText("Why it happens")).toBeTruthy();
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
