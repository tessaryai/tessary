// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Features. The toggle pins an org-wide override that starts unattended LLM spend, so the bugs
 * worth catching are the wrong key or value reaching the server, the build's default misreported, and a
 * member who cannot manage capabilities being offered the switch.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CapabilityOverrideView } from "../../api/types";
import { renderRoute } from "../../test/render";
import { Features } from "./Features";

const auth = vi.hoisted(() => ({
  listCapabilityOverrides: vi.fn(),
  setCapabilityOverride: vi.fn(),
  listMembers: vi.fn(),
}));
const session = vi.hoisted(() => ({ role: "owner" }));

vi.mock("../../api/client", () => ({ auth }));
vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => ({ user: { id: "u-me" } }) }));

const triage = (over: Partial<CapabilityOverrideView> = {}): CapabilityOverrideView => ({
  capability: "triage_automatic_enabled",
  enabled: false,
  default_enabled: false,
  has_override: false,
  ...over,
});

beforeEach(() => {
  session.role = "owner";
  auth.listCapabilityOverrides.mockResolvedValue([
    { capability: "rca_enabled", enabled: true, default_enabled: true, has_override: false },
    triage(),
  ]);
  auth.setCapabilityOverride.mockResolvedValue(triage({ enabled: true, has_override: true }));
  auth.listMembers.mockImplementation(async () => [{ user_id: "u-me", role: session.role }]);
});

const toggle = () => screen.getByRole("switch", { name: "Automatic triage" }) as HTMLButtonElement;

describe("reading", () => {
  it("shows only automatic triage, with the build's default", async () => {
    auth.listCapabilityOverrides.mockResolvedValue([triage({ enabled: true, default_enabled: true })]);
    renderRoute(<Features />);

    await screen.findByText("Automatic triage");
    expect(screen.getAllByRole("switch")).toHaveLength(1);
    expect(toggle().getAttribute("aria-checked")).toBe("true");
    expect(screen.getByText("Default:").textContent).toBe("Default: on");
  });
});

describe("changing", () => {
  it("pins the override on for the org", async () => {
    renderRoute(<Features />);
    await waitFor(() => expect(toggle().disabled).toBe(false));

    fireEvent.click(toggle());

    await waitFor(() =>
      expect(auth.setCapabilityOverride).toHaveBeenCalledWith("acme", "triage_automatic_enabled", true),
    );
  });

  it.each(["member", "viewer"])("offers no switch to a %s", async (role) => {
    session.role = role;
    renderRoute(<Features />);
    await screen.findByText("Automatic triage");
    await waitFor(() => expect(auth.listMembers).toHaveBeenCalled());

    expect(toggle().disabled).toBe(true);
  });
});
