// SPDX-License-Identifier: Apache-2.0
/*
 * CasePage's groundedness branch: the ranked causes with a "Show N answers" per cause, the rate with its
 * pins, the flagged answers filterable by cause, and the case's closing verbs. The capability and the
 * repository reads are mocked at the API, so the header's RCA controls and the no-repo notice come from
 * the same wiring the app runs.
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import type { CaseDetail, FlaggedAnswer, FlaggedAnswerPage, RcaReport } from "../../api/types";
import type { CapabilitiesView, Me } from "../../api/types-auth";
import { AuthProvider } from "../../auth/AuthContext";
import { ToastProvider } from "../../ui";
import { CasePage } from "./CasePage";
import { FLAGGED_ANSWER, GROUNDEDNESS_CASE_DETAIL, GROUNDEDNESS_REPORT } from "../../test/groundednessFixtures";

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

function capabilities(rcaEnabled: boolean): CapabilitiesView {
  return { capabilities: { rca_enabled: rcaEnabled } as CapabilitiesView["capabilities"] };
}

vi.mock("../../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/client")>();
  return { ...actual, auth: { ...actual.auth, me, getCapabilities } };
});

/** One answer per cause, each unlike the unfiltered first page's, so the list shows which filter it read. */
function causeAnswer(traceId: string, text: string): FlaggedAnswer {
  return {
    ...FLAGGED_ANSWER,
    traceId,
    spanId: `span-${traceId}`,
    answer: text,
    flaggedSentences: [{ start: 0, end: text.length, score: 0.99 }],
  };
}
const CAUSE_ANSWERS: Record<number, FlaggedAnswer> = {
  0: causeAnswer("t-1", "The old policy page says refunds run for 60 days."),
  1: causeAnswer("t-4", "The API allows 1,000 requests per minute per key."),
};

let caseDetail: CaseDetail = GROUNDEDNESS_CASE_DETAIL;
const getCase = vi.fn(async () => caseDetail);
const getFlaggedAnswers = vi.fn(
  async (_id: string, params?: { cause?: { rcaReport: string; index: number } }): Promise<FlaggedAnswerPage> => {
    const row = params?.cause ? CAUSE_ANSWERS[params.cause.index] : FLAGGED_ANSWER;
    return { rows: [row], total: 1, nextCursor: null };
  },
);
const getGitIntegration = vi.fn(async (): Promise<unknown> => null);

const api = { base: "/api/orgs/acme/projects/default", getCase, getFlaggedAnswers, getGitIntegration };

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
    useProjectApi: () => api,
  };
});

beforeEach(() => {
  me.mockResolvedValue(OWNER);
  getCapabilities.mockResolvedValue(capabilities(true));
});

afterEach(() => {
  cleanup();
  getCapabilities.mockReset();
  caseDetail = GROUNDEDNESS_CASE_DETAIL;
  getFlaggedAnswers.mockClear();
  getGitIntegration.mockImplementation(async () => null);
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
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

/** Waits for `read`'s answer to land, so what the page leaves out after it is settled rather than pending. */
async function settled(read: Mock) {
  await waitFor(() => expect(read).toHaveBeenCalled());
  await act(async () => {
    await read.mock.results.at(-1)?.value;
  });
}

function withReport(over: Partial<RcaReport>): CaseDetail {
  return { ...GROUNDEDNESS_CASE_DETAIL, rca: { ...GROUNDEDNESS_REPORT, ...over } };
}

const TITLE_1 = "The retriever still serves the old pricing and policy pages";
const FIX_1 = "Remove the old folders from the index sources and rebuild the index.";
const TITLE_2 = "The system prompt asks for a complete answer every time";
const FIX_2 = "Tell the agent to say when the documents don't answer the question.";
const NO_REPO =
  "These causes describe what the agent did. They aren't linked to a prompt or code because no repository was connected. Connect a repository and run RCA again to find them.";

describe("CasePage, groundedness", () => {
  it("ranks the causes, draws the rate, and filters the answers to the cause pressed", async () => {
    renderPage();

    await screen.findByRole("heading", { name: "Answers on support-agent became less grounded" });
    expect(
      screen.getByText(
        "Tessary identified 2 likely causes from the 58 flagged answers and the agent's repository.",
      ),
    ).toBeTruthy();
    // Each cause's title, then its fix, in the report's order.
    expect(
      screen.getAllByText(new RegExp(`^(${[TITLE_1, FIX_1, TITLE_2, FIX_2].join("|")})$`)).map((e) => e.textContent),
    ).toEqual([TITLE_1, `Suggested fix: ${FIX_1}`, TITLE_2, `Suggested fix: ${FIX_2}`]);
    expect(screen.getAllByRole("button", { name: /^Show \d+ answers$/ }).map((b) => b.textContent)).toEqual([
      "Show 3 answers",
      "Show 2 answers",
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

    fireEvent.click(screen.getByRole("button", { name: "Show 2 answers" }));

    await within(answers).findByRole("button", { name: /The API allows 1,000 requests/ });
    expect(within(answers).queryByRole("button", { name: /Refunds are available for up to 60 days/ })).toBeNull();
    expect(within(answers).queryByRole("button", { name: /The old policy page/ })).toBeNull();
    expect(within(filter).getByRole("button", { name: "Cause 2 · 2" }).getAttribute("aria-pressed")).toBe("true");

    expect(screen.getByRole("button", { name: "Re-run RCA" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Resolve case" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Mute case" })).toBeTruthy();
  });

  it("offers an owner Connect repository while the project has none, and drops it once one is connected", async () => {
    renderPage();
    await screen.findByRole("button", { name: "Re-run RCA" });
    await screen.findByRole("button", { name: "Connect repository" });
    cleanup();

    getGitIntegration.mockClear();
    getGitIntegration.mockImplementation(async () => ({ provider: "github", repoOwner: "acme", repoName: "agent" }));
    renderPage();
    await screen.findByRole("button", { name: "Re-run RCA" });
    await settled(getGitIntegration);
    expect(screen.queryByRole("button", { name: "Connect repository" })).toBeNull();
  });

  it("hides the RCA controls when the organization does not have RCA", async () => {
    getCapabilities.mockResolvedValue(capabilities(false));
    renderPage();

    // The finished report still reads: only running it again is withheld.
    await screen.findByText(TITLE_1);
    await settled(getCapabilities);
    expect(screen.queryByRole("button", { name: "Re-run RCA" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Connect repository" })).toBeNull();
  });

  it("says the causes are not tied to code when the run had no repository", async () => {
    caseDetail = withReport({ repo_available: false });
    renderPage();

    await screen.findByText(NO_REPO);
    expect(screen.getByText("Tessary identified 2 likely causes from the 58 flagged answers.")).toBeTruthy();
  });

  it("falls back to the report's summary when it named no cause", async () => {
    caseDetail = withReport({
      causes: [],
      verdict: "no_cause_found",
      summary: "The flagged answers share no cause the analysis could name.",
    });
    renderPage();

    await screen.findByText("The flagged answers share no cause the analysis could name.");
    expect(screen.queryByText(/Tessary identified/)).toBeNull();
    expect(screen.queryByRole("button", { name: /^Show \d+ answers?$/ })).toBeNull();
    expect(screen.queryByRole("group", { name: "Filter answers" })).toBeNull();
  });
});
