// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Features. Each toggle pins an org-wide override: automatic triage starts unattended LLM spend, prompt
 * management hides the Prompts page and refuses every prompt route. The bugs worth catching are the wrong key or
 * value reaching the server, the build's default misreported, a member who cannot manage capabilities being offered
 * a switch, and one row's pending write disabling the other row.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CapabilityOverrideView } from "../../api/types";
import { pending, renderRoute } from "../../test/render";
import { Features } from "./Features";
import { auth } from "../../test/api";

const session = vi.hoisted(() => ({ role: "owner" }));

vi.mock("../../api/client", () => import("../../test/api"));
vi.mock("../../tenant/TenantContext", () => import("../../test/api"));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => ({ user: { id: "u-me" } }) }));

const triage = (over: Partial<CapabilityOverrideView> = {}): CapabilityOverrideView => ({
  capability: "triage_automatic_enabled", enabled: false, default_enabled: false, has_override: false, licensed: true, ...over,
});
const prompts = (over: Partial<CapabilityOverrideView> = {}): CapabilityOverrideView => ({
  capability: "prompt_management_enabled", enabled: true, default_enabled: true, has_override: false, licensed: true, ...over,
});

beforeEach(() => {
  session.role = "owner";
  auth.listCapabilityOverrides.mockResolvedValue([
    { capability: "rca_enabled", enabled: true, default_enabled: true, has_override: false, licensed: true },
    triage(),
    prompts(),
  ]);
  auth.setCapabilityOverride.mockResolvedValue(triage({ enabled: true, has_override: true }));
  auth.listMembers.mockImplementation(async () => [{ user_id: "u-me", role: session.role }]);
});

const toggle = () => screen.getByRole("switch", { name: "Automatic triage" }) as HTMLButtonElement;
const promptToggle = () => screen.getByRole("switch", { name: "Prompt management" }) as HTMLButtonElement;

it("reading: shows automatic triage and prompt management, with each default", async () => {
  auth.listCapabilityOverrides.mockResolvedValue([triage({ enabled: true, default_enabled: true }), prompts({ enabled: false })]);
  renderRoute(<Features />);

  await screen.findByText("Automatic triage");
  expect(screen.getAllByRole("switch")).toHaveLength(2);
  expect(toggle().getAttribute("aria-checked")).toBe("true");
  expect(promptToggle().getAttribute("aria-checked")).toBe("false");
  expect(screen.getAllByText("Default:").map((d) => d.textContent)).toEqual(["Default: on", "Default: on"]);
  // Plain text: a backtick here would render literally.
  expect(
    screen.getByText(
      "Prompts, versions, and deployment labels for this organization, with the Prompts page, the /v1/prompts API, and the playground. Off hides the page and returns 403 on every prompt route.",
    ),
  ).toBeTruthy();
});

// Bug: a failed read shows an empty page, which reads as "no features".
it("says why the features could not be read", async () => {
  auth.listCapabilityOverrides.mockRejectedValue(new Error("AUTH.FORBIDDEN: not a member"));
  renderRoute(<Features />);

  expect((await screen.findByRole("alert")).textContent).toContain("AUTH.FORBIDDEN: not a member");
  expect(screen.queryByRole("switch")).toBeNull();
});

// Bug: a row drawn for a capability the server does not list, whose switch it would refuse.
it("draws no row for a capability the server does not list", async () => {
  auth.listCapabilityOverrides.mockResolvedValue([triage()]);
  renderRoute(<Features />);

  await screen.findByText("Automatic triage");
  expect(screen.getAllByRole("switch")).toHaveLength(1);
});

describe("changing", () => {
  // Bug: the wrong key or value sent, or a refused change that says nothing, so the reader believes it changed.
  it.each([
    ["pins the override on for the org", () => Promise.resolve(triage({ enabled: true })), "Automatic triage is on"],
    ["says why a change was refused", () => Promise.reject(new Error("Only an owner can change this")), "Only an owner can change this"],
  ])("%s", async (_, answer, said) => {
    auth.setCapabilityOverride.mockImplementation(answer);
    renderRoute(<Features />);
    await waitFor(() => expect(toggle().disabled).toBe(false));

    fireEvent.click(toggle());

    expect(await screen.findByText(said)).toBeTruthy();
    expect(auth.setCapabilityOverride).toHaveBeenCalledWith("acme", "triage_automatic_enabled", true);
  });

  // Bug: the prompt row sends the triage key, or reports the triage toast.
  it("turns prompt management off with its own key and toast", async () => {
    auth.setCapabilityOverride.mockResolvedValue(prompts({ enabled: false, has_override: true }));
    renderRoute(<Features />);
    await waitFor(() => expect(promptToggle().disabled).toBe(false));

    fireEvent.click(promptToggle());

    expect(await screen.findByText("Prompt management is off")).toBeTruthy();
    expect(auth.setCapabilityOverride).toHaveBeenCalledWith("acme", "prompt_management_enabled", false);
  });

  it("turns prompt management on, and says why a change was refused", async () => {
    auth.listCapabilityOverrides.mockResolvedValue([triage(), prompts({ enabled: false })]);
    auth.setCapabilityOverride.mockResolvedValueOnce(prompts({ enabled: true, has_override: true }));
    auth.setCapabilityOverride.mockRejectedValueOnce(new Error("CAPABILITY.FORBIDDEN: no"));
    renderRoute(<Features />);
    await waitFor(() => expect(promptToggle().disabled).toBe(false));

    fireEvent.click(promptToggle());
    expect(await screen.findByText("Prompt management is on")).toBeTruthy();
    await waitFor(() => expect(promptToggle().disabled).toBe(false));
    fireEvent.click(promptToggle());

    expect(await screen.findByText("Could not change prompt management")).toBeTruthy();
  });

  // Bug: one shared mutation, so a write on one row disables the other row's switch.
  it("keeps the other switch usable while one write is pending", async () => {
    auth.setCapabilityOverride.mockReturnValue(pending());
    renderRoute(<Features />);
    await waitFor(() => expect(promptToggle().disabled).toBe(false));

    fireEvent.click(promptToggle());

    await waitFor(() => expect(promptToggle().disabled).toBe(true));
    expect(toggle().disabled).toBe(false);
  });

  it.each(["member", "viewer"])("offers no switch to a %s", async (role) => {
    session.role = role;
    renderRoute(<Features />);
    await screen.findByText("Automatic triage");
    await waitFor(() => expect(auth.listMembers).toHaveBeenCalled());

    expect(toggle().disabled).toBe(true);
    expect(promptToggle().disabled).toBe(true);
  });
});
