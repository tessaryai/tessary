// SPDX-License-Identifier: Apache-2.0
/*
 * CasePage's groundedness branch: the ranked causes with a "Show N answers" per cause, the rate with its
 * pins, the flagged answers filterable by cause, and the case's closing verbs.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { CasePage } from "./CasePage";
import { FLAGGED_ANSWER, GROUNDEDNESS_CASE_DETAIL } from "../../test/groundednessFixtures";

const getCase = vi.fn(async () => GROUNDEDNESS_CASE_DETAIL);
const getFlaggedAnswers = vi.fn(async () => ({ rows: [FLAGGED_ANSWER], total: 1, nextCursor: null }));

const api = { base: "/api/orgs/acme/projects/default", getCase, getFlaggedAnswers };

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({ orgSlug: "acme", projectSlug: "default", api }),
    useProjectApi: () => api,
  };
});
vi.mock("../../capabilities/useCapabilities", () => ({
  useCapabilities: () => ({ isEnabled: () => true }),
}));
vi.mock("../components/useRepoPrompt", () => ({ useRepoPrompt: () => ({ canPrompt: false }) }));
vi.mock("../components/ConnectRepositoryDialog", () => ({ ConnectRepositoryDialog: () => null }));

afterEach(() => {
  cleanup();
  getFlaggedAnswers.mockClear();
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/orgs/acme/projects/default/cases/case-1"]}>
        <Routes>
          <Route path="/orgs/:orgSlug/projects/:projectSlug/cases/:caseId" element={<CasePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("CasePage, groundedness", () => {
  it("ranks the causes, draws the rate, and filters the answers by cause", async () => {
    // jsdom has no layout, so there is nothing to scroll to.
    Element.prototype.scrollIntoView = vi.fn();
    renderPage();

    await screen.findByRole("heading", { name: "Answers on support-agent became less grounded" });
    expect(
      screen.getByText(
        "Tessary identified 2 likely causes from the 58 flagged answers and the agent's repository.",
      ),
    ).toBeTruthy();
    expect(screen.getByRole("button", { name: "Show 3 answers" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Show 2 answers" })).toBeTruthy();

    expect(screen.getByText("6.4% of answers flagged")).toBeTruthy();
    expect(screen.getByText(/This rate opened this case\./)).toBeTruthy();

    const filter = screen.getByRole("group", { name: "Filter answers" });
    expect(filter.textContent).toContain("All · 58");
    expect(filter.textContent).toContain("Cause 1 · 3");
    expect(filter.textContent).toContain("Cause 2 · 2");
    expect(document.querySelectorAll("mark")).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "Show 2 answers" }));
    await waitFor(() =>
      expect(getFlaggedAnswers).toHaveBeenCalledWith(
        "fnd-1",
        expect.objectContaining({ cause: { rcaReport: "rca-1", index: 1 } }),
      ),
    );
    expect(screen.getByRole("button", { name: "Cause 2 · 2" }).getAttribute("aria-pressed")).toBe("true");

    expect(screen.getByRole("button", { name: "Resolve case" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Mute case" })).toBeTruthy();
  });
});
