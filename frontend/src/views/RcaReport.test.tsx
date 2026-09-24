// SPDX-License-Identifier: Apache-2.0
/*
 * RcaReport's ranked causes: a groundedness report counts each cause in traces with a flagged answer and
 * links those traces, and a frustration report counts sessions and links the sessions and their turns.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
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
  getRcaReport: vi.fn(async () => report),
  getGitIntegration: vi.fn(async () => null),
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
});

function renderReport() {
  me.mockResolvedValue({ id: "user-1", email: "a@example.com", orgs: [], platform_staff: false });
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
});
