// SPDX-License-Identifier: Apache-2.0
/*
 * The classifiers page: every open finding with its ruling, counted in the heading. The bugs worth
 * catching: a closed finding shown as open, an open count that miscounts, a row or case link that
 * opens the wrong page, and a failed catalog read claiming nothing is switched on.
 */
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { BehaviorFinding, Classifier } from "../../api/types";
import { currentLocation, pending, renderRoute } from "../../test/render";
import { ClassifiersPage } from "./ClassifiersPage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listClassifiers: vi.fn(),
  listBehaviorFindings: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const classifier = (id: string, enabled: boolean) => ({ id, detector: id, enabled }) as Classifier;
const finding = (id: string, over: Partial<BehaviorFinding> = {}) =>
  ({
    id,
    title: `Finding ${id}`,
    detector: "tool_error",
    firstSeenAt: new Date().toISOString(),
    triageStatus: "pending",
    triageAction: null,
    triageVerdict: null,
    humanVerdictAt: null,
    caseId: null,
    ...over,
  }) as BehaviorFinding;
const opened = (id: string, caseId: string | null = `case-${id}`) =>
  finding(id, { triageStatus: "done", triageVerdict: "positive", triageAction: "opened_case", caseId });

beforeEach(() => {
  api.listClassifiers.mockResolvedValue([classifier("tool_error", true), classifier("cost_drift", true), classifier("loop", false)]);
  api.listBehaviorFindings.mockResolvedValue({ findings: [] });
});

const renderPage = () =>
  renderRoute(<ClassifiersPage />, {
    route: "/orgs/acme/projects/default/classifiers",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers",
  });
const section = (title: string) => screen.getByRole("heading", { name: title }).closest("section")!;

describe("ClassifiersPage", () => {
  it("counts what is switched on, and says an empty page is the healthy state", async () => {
    renderPage();

    expect(await screen.findByRole("link", { name: "Catalog · 2 of 3 on" })).toBeTruthy();
    expect(await screen.findByText(/No findings/)).toBeTruthy();
    expect(api.listBehaviorFindings).toHaveBeenCalledWith();
  });

  it("does not claim nothing is on when the catalog could not be read", async () => {
    api.listClassifiers.mockRejectedValue(new Error("catalog unavailable"));
    renderPage();

    expect(await screen.findByText("catalog unavailable")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Catalog" })).toBeTruthy();
  });

  it("shows the loading row, then a failed read of the findings", async () => {
    api.listBehaviorFindings.mockReturnValueOnce(pending());
    renderPage();
    expect(screen.queryByText(/No findings/)).toBeNull();
    cleanup();

    api.listBehaviorFindings.mockRejectedValue(new Error("findings unavailable"));
    renderPage();
    expect(await screen.findByText("findings unavailable")).toBeTruthy();
  });

  it("counts the open findings in the heading, and leaves the closed ones off", async () => {
    api.listBehaviorFindings.mockResolvedValue({
      findings: [
        finding("a"),
        finding("b", { triageStatus: "done", triageVerdict: "negative", triageAction: "closed" }),
        finding("c", { triageStatus: "failed" }),
      ],
    });
    renderPage();

    await screen.findByText("Finding a");
    expect(within(section("Open")).getByText("2")).toBeTruthy();
    expect(within(section("Open")).getByText("Finding c")).toBeTruthy();
    expect(screen.queryByText("Finding b")).toBeNull();
    expect(within(section("Open")).getByText("Triage failed").className).toContain("text-error");
    expect(screen.queryByText(/awaiting triage/)).toBeNull();
  });

  it("reads as empty when every finding is closed", async () => {
    api.listBehaviorFindings.mockResolvedValue({
      findings: [finding("b", { triageStatus: "done", triageVerdict: "negative", triageAction: "closed" })],
    });
    renderPage();

    expect(await screen.findByText(/No findings/)).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "Open" })).toBeNull();
  });

  it("opens a finding from its row, and its case from the case link without opening the finding", async () => {
    api.listBehaviorFindings.mockResolvedValue({ findings: [opened("f/1"), opened("f-2", null)] });
    renderPage();

    fireEvent.click(await screen.findByRole("link", { name: "Open case" }));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/cases/case-f%2F1");
    cleanup();

    renderPage();
    fireEvent.click(await screen.findByText("Finding f/1"));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/classifiers/findings/f%2F1");
  });

  it("links a case only for a ruling that opened one", async () => {
    api.listBehaviorFindings.mockResolvedValue({ findings: [opened("f-2", null), finding("f-3", { caseId: "case-x" })] });
    renderPage();

    await screen.findByText("Finding f-2");
    expect(screen.queryByRole("link", { name: "Open case" })).toBeNull();
  });
});
