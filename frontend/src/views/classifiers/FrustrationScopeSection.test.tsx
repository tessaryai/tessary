// SPDX-License-Identifier: Apache-2.0
/*
 * The Frustration call-site picker. The bugs worth catching: a stored pick shown unchecked, a save that sends
 * the wrong ids or keeps an unchecked one, and a classifier with no pick that does not say it scores nothing.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Classifier } from "../../api/types";
import { renderRoute } from "../../test/render";
import { FrustrationScopeSection } from "./FrustrationScopeSection";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  getPipeline: vi.fn(),
  getFrustrationScope: vi.fn(),
  setFrustrationScope: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ api }) }));

const FRUSTRATION = { id: "clf-f", detector: "frustration" } as Classifier;

function pipeline(callSites: { id: string; use_case: string | null; shape: string | null }[]) {
  return { ok: true, path: "", errors: [], repairs: [], pipeline: { call_sites: callSites } };
}

const CALL_SITES = [
  { id: "cs-router", use_case: "Route the message to a lane", shape: "route" },
  { id: "cs-reply", use_case: "Reply to the user", shape: "conversational_turn" },
  { id: "cs-memory", use_case: null, shape: null },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.getPipeline.mockResolvedValue(pipeline(CALL_SITES));
  api.getFrustrationScope.mockResolvedValue({ call_site_ids: ["cs-reply"] });
});

const box = (name: string) => screen.getByRole("checkbox", { name: new RegExp(name) }) as HTMLInputElement;
const save = () => fireEvent.click(screen.getByRole("button", { name: "Save call sites" }));

describe("FrustrationScopeSection", () => {
  it("lists every call site, named by its use case, with the stored pick checked", async () => {
    renderRoute(<FrustrationScopeSection classifier={FRUSTRATION} />);

    await waitFor(() => expect(box("Reply to the user").checked).toBe(true));
    expect(api.getFrustrationScope).toHaveBeenCalledWith("clf-f");
    expect(box("Route the message to a lane").checked).toBe(false);
    expect(box("cs-memory").checked).toBe(false);
    expect(screen.getByText("Chat turn")).toBeTruthy();
  });

  it("saves exactly the checked call sites", async () => {
    api.setFrustrationScope.mockResolvedValue({ call_site_ids: ["cs-memory"] });
    renderRoute(<FrustrationScopeSection classifier={FRUSTRATION} />);
    await waitFor(() => expect(box("Reply to the user").checked).toBe(true));

    fireEvent.click(box("Reply to the user"));
    fireEvent.click(box("cs-memory"));
    save();

    await waitFor(() => expect(api.setFrustrationScope).toHaveBeenCalledWith("clf-f", { call_site_ids: ["cs-memory"] }));
    expect(await screen.findByText("Call sites saved")).toBeTruthy();
  });

  it("says nothing is scored while no call site is picked", async () => {
    api.getFrustrationScope.mockResolvedValue({ call_site_ids: [] });
    renderRoute(<FrustrationScopeSection classifier={FRUSTRATION} />);

    expect(
      await screen.findByText("Nothing is scored until you pick a call site."),
    ).toBeTruthy();
  });

  it("says when the project has no call sites to pick", async () => {
    api.getPipeline.mockResolvedValue(pipeline([]));
    api.getFrustrationScope.mockResolvedValue({ call_site_ids: [] });
    renderRoute(<FrustrationScopeSection classifier={FRUSTRATION} />);

    expect(await screen.findByText(/No call sites yet/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Save call sites" })).toBeNull();
  });
});
