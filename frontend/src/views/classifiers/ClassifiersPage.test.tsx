// SPDX-License-Identifier: Apache-2.0
/*
 * The Classifiers page while its charts are being built. The open findings moved to Triage and the catalog moved
 * to one configure page per classifier, so the bugs worth catching are: the findings listed here a second time, a
 * classifier whose configure page nothing links to, and a failed read that reads as "no classifiers".
 */
import { fireEvent, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Classifier } from "../../api/types";
import { currentLocation, renderRoute } from "../../test/render";
import { ClassifiersPage } from "./ClassifiersPage";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listClassifiers: vi.fn(),
  listBehaviorFindings: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({
  useTenant: () => ({ api, orgSlug: "acme", projectSlug: "default" }),
}));

const classifier = (id: string, name: string, enabled: boolean) =>
  ({ id, name, detector: id, enabled }) as Classifier;

beforeEach(() => {
  api.listClassifiers.mockResolvedValue([classifier("tool_error", "Tool errors", true), classifier("cost/drift", "Cost drift", false)]);
  api.listBehaviorFindings.mockResolvedValue({ findings: [] });
});

const renderPage = () =>
  renderRoute(<ClassifiersPage />, {
    route: "/orgs/acme/projects/default/classifiers",
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "classifiers",
  });

describe("ClassifiersPage", () => {
  // Bug: with the catalog gone, a classifier's configure page is reachable only by typing its URL.
  it("links every classifier to its configure page, saying whether it is on", async () => {
    renderPage();

    const cost = await screen.findByRole("link", { name: /Cost drift/ });
    expect(cost.textContent).toContain("Off");
    expect(screen.getByRole("link", { name: /Tool errors/ }).textContent).toContain("On");
    fireEvent.click(cost);
    expect(currentLocation()).toBe("/orgs/acme/projects/default/classifiers/cost%2Fdrift");
  });

  // Bug: the open findings listed here as well as on Triage, two lists of one queue that drift apart.
  it("lists no findings: they live on Triage", async () => {
    renderPage();

    await screen.findByRole("link", { name: /Cost drift/ });
    expect(api.listBehaviorFindings).not.toHaveBeenCalled();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("says why the classifiers could not be read", async () => {
    api.listClassifiers.mockRejectedValue(new Error("classifiers unavailable"));
    renderPage();

    expect(await screen.findByText("classifiers unavailable")).toBeTruthy();
  });
});
