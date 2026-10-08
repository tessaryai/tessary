// SPDX-License-Identifier: Apache-2.0
/*
 * The call-site scope editor on a classifier's configure page. The bugs worth catching: a saved list sent under the wrong
 * name, "Every call site" sent as an empty list (the server refuses it, and an empty list is not "everywhere"),
 * a stored list not shown when the page opens again, and a save allowed with nothing picked.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Classifier } from "../../api/types";
import { renderRoute } from "../../test/render";
import { CallSitesSection } from "./CallSitesSection";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listClassifierCallSites: vi.fn(),
  setClassifierCallSites: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const EVERYWHERE = { id: "clf-1", detector: "secret_leak", call_site_ids: null } as Classifier;
const SCOPED = { ...EVERYWHERE, call_site_ids: ["cs-search"] } as Classifier;

beforeEach(() => {
  api.listClassifierCallSites.mockResolvedValue(["cs-answer", "cs-search", "cs-summary"]);
});

const toggle = (callSite: string) => screen.getByRole("switch", { name: `Run on ${callSite}` });
const save = () => fireEvent.click(screen.getByRole("button", { name: "Save call sites" }));

describe("CallSitesSection", () => {
  it("limits the classifier to the call sites picked, under call_site_ids", async () => {
    api.setClassifierCallSites.mockResolvedValue({ ...EVERYWHERE, call_site_ids: ["cs-answer", "cs-summary"] });
    renderRoute(<CallSitesSection classifier={EVERYWHERE} />);

    fireEvent.click(await screen.findByRole("button", { name: "Only some" }));
    fireEvent.click(toggle("cs-answer"));
    fireEvent.click(toggle("cs-summary"));
    save();

    await waitFor(() => expect(api.setClassifierCallSites).toHaveBeenCalledWith("clf-1", ["cs-answer", "cs-summary"]));
    expect(await screen.findByText("Call sites saved")).toBeTruthy();
  });

  it("opens on the stored list, and going back to every call site sends null", async () => {
    api.setClassifierCallSites.mockResolvedValue(EVERYWHERE);
    renderRoute(<CallSitesSection classifier={SCOPED} />);

    await waitFor(() => expect(toggle("cs-search").getAttribute("aria-checked")).toBe("true"));
    expect(toggle("cs-answer").getAttribute("aria-checked")).toBe("false");

    fireEvent.click(screen.getByRole("button", { name: "Every call site" }));
    save();

    await waitFor(() => expect(api.setClassifierCallSites).toHaveBeenCalledWith("clf-1", null));
  });

  it("does not save a list with nothing picked", async () => {
    renderRoute(<CallSitesSection classifier={SCOPED} />);
    await waitFor(() => expect(toggle("cs-search").getAttribute("aria-checked")).toBe("true"));

    fireEvent.click(toggle("cs-search"));
    save();

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("alert").textContent).toBe("Pick at least one call site, or choose Every call site.");
    expect(api.setClassifierCallSites).not.toHaveBeenCalled();
  });
});
