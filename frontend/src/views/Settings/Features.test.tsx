// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Features. Each toggle pins an org-wide override: automatic triage, automatic RCA and Fix PRs start
 * unattended LLM spend, and prompt management hides the Prompts page and refuses every prompt route. The bugs worth
 * catching are the wrong key or value reaching the server, the build's default misreported, a member who cannot
 * manage capabilities being offered the switch, and Fix PRs reading as working in a project that cannot open a
 * pull request.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CapabilityOverrideView } from "../../api/types";
import { renderRoute } from "../../test/render";
import { Features } from "./Features";
import { api, auth } from "../../test/api";

const session = vi.hoisted(() => ({ role: "owner" }));

vi.mock("../../api/client", () => import("../../test/api"));
vi.mock("../../tenant/TenantContext", () => import("../../test/api"));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => ({ user: { id: "u-me" } }) }));

const override = (capability: string, over: Partial<CapabilityOverrideView> = {}): CapabilityOverrideView => ({
  capability, enabled: false, default_enabled: false, has_override: false, licensed: true, ...over,
});
const triage = (over: Partial<CapabilityOverrideView> = {}) => override("triage_automatic_enabled", over);

const WRITABLE = { repoOwner: "acme", repoName: "app", defaultBranch: "main", provider: "github", host: "", canWrite: true, writeReason: null };
const fixLane = (effective: string | null) => ({
  lanes: [{ id: "fix", group: "agent_vm", label: "Fix", description: "", provider_options: [], effective_model_key: effective, automatic: true }],
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
  api.getGitIntegration.mockResolvedValue(WRITABLE);
  api.getModelSettings.mockResolvedValue(fixLane("ANTHROPIC:claude-frontier"));
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

// Bug: a feature the server lists had no row, a row showed the wrong default, or a capability that is not an
// automatic feature (rca_enabled) got a switch.
it("reading: shows automatic triage, automatic RCA and Fix PRs, each with the build's default", async () => {
  auth.listCapabilityOverrides.mockResolvedValue([
    { capability: "rca_enabled", enabled: true, default_enabled: true, has_override: false },
    triage(),
    override("rca_automatic_enabled", { enabled: true, has_override: true }),
    override("fix_pr_enabled", { default_enabled: true, enabled: true }),
  ]);
  renderRoute(<Features />);

  await screen.findByText("Fix PRs");
  expect(screen.getAllByRole("switch").map((s) => s.getAttribute("aria-label"))).toEqual([
    "Automatic triage", "Automatic RCA", "Fix PRs",
  ]);
  expect(screen.getByRole("switch", { name: "Automatic RCA" }).getAttribute("aria-checked")).toBe("true");
  expect(screen.getAllByText("Default:").map((d) => d.textContent)).toEqual(["Default: off", "Default: off", "Default: on"]);
});

describe("Fix PRs in this project", () => {
  const all = () => [triage(), override("fix_pr_enabled", { enabled: true })];

  // Bug: the switch read on in a project whose repository, connection or model cannot open a pull request, so the
  // owner waits for a pull request that never comes; or the reason named a later gate than the one the backend stops at.
  it.each([
    ["no repository", { git: null, model: "ANTHROPIC:claude-frontier" },
      "This project has no repository connected, so Tessary cannot open a pull request.", "Open Git integration"],
    ["a read-only connection", { git: { ...WRITABLE, canWrite: false, writeReason: "TOKEN_CANNOT_PUSH" }, model: null },
      "This project's repository connection cannot write, so Tessary cannot open a pull request.", "Open Git integration"],
    ["no frontier model", { git: WRITABLE, model: null },
      "This project has no frontier model for the fix, so Tessary cannot open a pull request.", "Open Models"],
  ])("says why it cannot run with %s, and keeps the switch usable", async (_, { git, model }, said, link) => {
    auth.listCapabilityOverrides.mockResolvedValue(all());
    api.getGitIntegration.mockResolvedValue(git);
    api.getModelSettings.mockResolvedValue(fixLane(model));
    renderRoute(<Features />);

    expect(await screen.findByText(said)).toBeTruthy();
    expect(screen.getByRole("link", { name: link })).toBeTruthy();
    expect((screen.getByRole("switch", { name: "Fix PRs" }) as HTMLButtonElement).disabled).toBe(false);
  });

  // Bug: a reason shown when the project can open pull requests, or claimed while the reads are still on their way.
  it("says nothing when the project can open a pull request, nor while it is still reading", async () => {
    auth.listCapabilityOverrides.mockResolvedValue(all());
    api.getGitIntegration.mockReturnValue(new Promise(() => {}));
    api.getModelSettings.mockResolvedValue(fixLane(null));
    renderRoute(<Features />);
    await screen.findByText("Fix PRs");
    await waitFor(() => expect(api.getModelSettings).toHaveBeenCalled());

    expect(screen.queryByText(/cannot open a pull request/)).toBeNull();
  });

  it("says nothing once every gate is open", async () => {
    auth.listCapabilityOverrides.mockResolvedValue(all());
    renderRoute(<Features />);
    await screen.findByText("Fix PRs");
    await waitFor(() => expect(api.getModelSettings).toHaveBeenCalled());
    await waitFor(() => expect(api.getGitIntegration).toHaveBeenCalled());

    expect(screen.queryByText(/cannot open a pull request/)).toBeNull();
  });
});

describe("changing", () => {
  // Bug: every row sent the triage key, so turning on Fix PRs turned on automatic triage.
  it("sends the key of the row that was switched", async () => {
    auth.listCapabilityOverrides.mockResolvedValue([triage(), override("fix_pr_enabled")]);
    auth.setCapabilityOverride.mockResolvedValue(override("fix_pr_enabled", { enabled: true }));
    renderRoute(<Features />);
    const fix = (await screen.findByRole("switch", { name: "Fix PRs" })) as HTMLButtonElement;
    await waitFor(() => expect(fix.disabled).toBe(false));

    fireEvent.click(fix);

    expect(await screen.findByText("Fix PRs are on")).toBeTruthy();
    expect(auth.setCapabilityOverride).toHaveBeenCalledWith("acme", "fix_pr_enabled", true);
  });

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
  it.each(["member", "viewer"])("offers no switch to a %s", async (role) => {
    session.role = role;
    renderRoute(<Features />);
    await screen.findByText("Automatic triage");
    await waitFor(() => expect(auth.listMembers).toHaveBeenCalled());

    expect(toggle().disabled).toBe(true);
    expect(promptToggle().disabled).toBe(true);
  });
});
