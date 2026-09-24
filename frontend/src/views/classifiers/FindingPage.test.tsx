// SPDX-License-Identifier: Apache-2.0
/*
 * FindingPage's groundedness branch: the title from the finding, the rate and its two pins, the triage
 * verbs before a ruling, and the flagged answers in place of the generic evidence table.
 */
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { FindingPage } from "./FindingPage";
import type { BehaviorFindingDetail } from "../../api/types";
import { GROUNDEDNESS_FINDING_DETAIL } from "../../test/groundednessFixtures";
import { dateTime } from "./groundedness";

let findingDetail: BehaviorFindingDetail = GROUNDEDNESS_FINDING_DETAIL;
const getBehaviorFinding = vi.fn(async () => findingDetail);
const getBehaviorFindingEvidence = vi.fn();
const getFlaggedAnswers = vi.fn();

vi.mock("../../tenant/TenantContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../tenant/TenantContext")>();
  return {
    ...actual,
    useTenant: () => ({
      orgSlug: "acme",
      projectSlug: "default",
      api: { base: "/api/orgs/acme/projects/default", getBehaviorFinding, getBehaviorFindingEvidence, getFlaggedAnswers },
    }),
  };
});

afterEach(() => {
  cleanup();
  findingDetail = GROUNDEDNESS_FINDING_DETAIL;
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={["/orgs/acme/projects/default/classifiers/findings/fnd-1"]}>
        <Routes>
          <Route path="/orgs/:orgSlug/projects/:projectSlug/classifiers/findings/:findingId" element={<FindingPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("FindingPage, groundedness", () => {
  it("tells the rate and shows the flagged answers instead of the evidence table", async () => {
    renderPage();

    await screen.findByText("Answers on support-agent became less grounded");
    const since = dateTime("2026-09-23T09:12:00Z");
    expect(
      screen.getByText(`since ${since} vs the rate it learned from its first 1,000 traces`),
    ).toBeTruthy();
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
    expect(getBehaviorFindingEvidence).not.toHaveBeenCalled();
  });

  it("shows a triaged finding's ruling and its case in place of the triage verbs", async () => {
    findingDetail = {
      ...GROUNDEDNESS_FINDING_DETAIL,
      finding: {
        ...GROUNDEDNESS_FINDING_DETAIL.finding,
        caseId: "case-1",
        status: "closed",
        triageStatus: "done",
        triageVerdict: "positive",
        triageAction: "opened_case",
        triagedAt: "2026-09-23T13:52:00Z",
      },
    };
    renderPage();

    const toCase = await screen.findByRole("link", { name: "View case" });
    expect(toCase.getAttribute("href")).toBe("/orgs/acme/projects/default/cases/case-1");
    expect(screen.getByText("Positive")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Run triage" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Confirm and open a case" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Absorb as legitimate" })).toBeNull();
  });
});
