// SPDX-License-Identifier: Apache-2.0
/*
 * RcaReport's ranked causes: a groundedness report counts each cause in traces with a flagged answer and
 * links those traces, and a frustration report counts sessions and links the sessions and their turns.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { RcaReport as RcaReportData } from "../api/types";
import type { Me } from "../api/types-auth";
import { AuthProvider } from "../auth/AuthContext";
import { ToastProvider } from "../ui";
import { RcaReport } from "./RcaReport";
import { GROUNDEDNESS_REPORT } from "../test/groundednessFixtures";

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
  cleanup();
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
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <AuthProvider>
          <MemoryRouter initialEntries={["/orgs/acme/projects/default/rca/rca-1"]}>
            <Routes>
              <Route path="/orgs/:orgSlug/projects/:projectSlug/rca/:reportId" element={<RcaReport />} />
            </Routes>
          </MemoryRouter>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

/** Each evidence link's label and where it goes, in page order. */
function evidenceLinks() {
  return screen
    .getAllByRole("link", { name: /^(trace|turn|session) …/ })
    .map((a) => [a.textContent, a.getAttribute("href")]);
}

const EXPLORE = "/orgs/acme/projects/default/explore?trace=";

describe("RcaReport", () => {
  it("counts a groundedness report's causes in traces and links the traces", async () => {
    renderReport();

    await screen.findByText("The retriever still serves the old pricing and policy pages");
    screen.getByText(/^Call site support-agent · Flagged answers rose to 6\.4% from a learned 2\.1% · .+ onward$/);
    expect(screen.getByText("3 traces")).toBeTruthy();
    expect(screen.getByText("2 traces")).toBeTruthy();
    expect(screen.queryByText(/\d+ sessions?$/)).toBeNull();
    expect(evidenceLinks()).toEqual([
      ["trace …t-1", `${EXPLORE}t-1`],
      ["trace …t-2", `${EXPLORE}t-2`],
      ["trace …t-3", `${EXPLORE}t-3`],
      ["trace …t-4", `${EXPLORE}t-4`],
      ["trace …t-5", `${EXPLORE}t-5`],
    ]);
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
          what_the_agent_did: "Asked for the order number after the user gave it.",
          fix_suggestion: "Read the order number from the conversation before asking.",
          attribution: null,
          evidence_session_ids: ["session-0000aa01", "session-0000aa02"],
          evidence_trace_ids: ["trace-0000bb01"],
          sessions_affected: 4,
          traces_affected: 1,
        },
      ],
    };
    renderReport();

    await screen.findByText("The agent repeats the same clarifying question");
    screen.getByText(/^Call site support-agent · Frustrated sessions rose to 5\.2% from a learned 1\.8% · .+ onward$/);
    expect(screen.getByText("4 sessions")).toBeTruthy();
    expect(screen.queryByText("1 trace")).toBeNull();
    expect(evidenceLinks()).toEqual([
      ["session …0000aa01", "/orgs/acme/projects/default/sessions/session-0000aa01"],
      ["session …0000aa02", "/orgs/acme/projects/default/sessions/session-0000aa02"],
      ["turn …0000bb01", `${EXPLORE}trace-0000bb01`],
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
        { ...GROUNDEDNESS_REPORT.causes[1], attribution: { kind: "code", path: null, commit: null, excerpt: null } },
      ],
    };
    renderReport();

    expect(await screen.findByText("agent/system.md @ c0ffee12")).toBeTruthy();
    expect(screen.getByText("Prompt")).toBeTruthy();
    expect(screen.getByText("Code")).toBeTruthy();
    expect(screen.queryByText("Not tied to a line in the repo")).toBeNull();
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
