// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Members. The controls mirror the server's MEMBERS_MANAGE rule (owner and admin manage;
 * only an owner can grant the owner role or touch another owner). The server re-checks every call,
 * so the bug these tests catch is the page offering a control that will only ever be refused, or
 * sending the wrong person or role when it is used. Beside it: the instance-wide sign-up policy,
 * editable only through the organization that governs it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import type { OrgInvitation, OrgMember, OrgRole, SignupPolicy } from "../../api/types-auth";
import { ApiError } from "../../api/types";
import { pending, renderRoute } from "../../test/render";
import { Members } from "./Members";

const auth = vi.hoisted(() => ({
  listMembers: vi.fn(),
  listInvitations: vi.fn(),
  getSignupPolicy: vi.fn(),
  updateSignupPolicy: vi.fn(),
  addMember: vi.fn(),
  updateMember: vi.fn(),
  removeMember: vi.fn(),
  revokeInvitation: vi.fn(),
}));
const session = vi.hoisted(() => ({ userId: "u-me" }));

vi.mock("../../api/client", () => ({ auth }));
vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => ({ user: { id: session.userId } }) }));

const member = (user_id: string, role: OrgRole, over: Partial<OrgMember> = {}): OrgMember => ({
  user_id,
  email: `${user_id}@acme.com`,
  display_name: null,
  avatar_url: null,
  role,
  created_at: "2026-01-15T00:00:00Z",
  ...over,
});

const POLICY: SignupPolicy = { mode: "open", domains: [], governing: true, governing_org_slug: "acme" };

function roster(myRole: OrgRole) {
  return [member("u-me", myRole, { display_name: "Me Myself" }), member("u-owner2", "owner"), member("u-dev", "member")];
}

let confirmReply = true;
beforeEach(() => {
  session.userId = "u-me";
  confirmReply = true;
  vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);
  auth.listMembers.mockResolvedValue(roster("owner"));
  auth.listInvitations.mockResolvedValue([]);
  auth.getSignupPolicy.mockResolvedValue(POLICY);
  auth.updateMember.mockResolvedValue({});
  auth.removeMember.mockResolvedValue(null);
  auth.revokeInvitation.mockResolvedValue(null);
  auth.updateSignupPolicy.mockResolvedValue(POLICY);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

/** Let a mutation a click may have started reach the API, so "not called" means not called. */
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));

const row = (email: string) => screen.getAllByText(email)[0].closest<HTMLElement>("div.flex")!;

describe("as an owner", () => {
  it("can re-role and remove anyone but themself, owners included", async () => {
    renderRoute(<Members />);
    await screen.findAllByText("u-dev@acme.com");

    expect(within(row("u-me@acme.com")).queryByRole("combobox")).toBeNull();
    expect(within(row("u-me@acme.com")).getByText("Owner · you")).toBeTruthy();
    expect(within(row("u-me@acme.com")).queryByRole("button", { name: "Remove" })).toBeNull();

    const ownerSelect = within(row("u-owner2@acme.com")).getByRole("combobox") as HTMLSelectElement;
    expect((within(ownerSelect).getByRole("option", { name: "Owner" }) as HTMLOptionElement).disabled).toBe(false);

    const devSelect = within(row("u-dev@acme.com")).getByRole("combobox");
    fireEvent.change(devSelect, { target: { value: "member" } });
    expect(auth.updateMember).not.toHaveBeenCalled();
    fireEvent.change(devSelect, { target: { value: "admin" } });
    await waitFor(() => expect(auth.updateMember).toHaveBeenCalledWith("acme", "u-dev", { role: "admin" }));
    expect(await screen.findByText("Role updated")).toBeTruthy();

    fireEvent.click(within(row("u-owner2@acme.com")).getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(auth.removeMember).toHaveBeenCalledWith("acme", "u-owner2"));
    expect(await screen.findByText("Member removed")).toBeTruthy();
  });

  it("removes nobody when the confirmation is declined, and says why a change was refused", async () => {
    confirmReply = false;
    auth.updateMember.mockRejectedValue(new ApiError(403, { code: "AUTH.FORBIDDEN", message: "last owner" }));
    auth.removeMember.mockRejectedValue(new ApiError(409, { code: "ORG.LAST_OWNER", message: "cannot remove" }));
    renderRoute(<Members />);
    await screen.findAllByText("u-dev@acme.com");

    fireEvent.click(within(row("u-dev@acme.com")).getByRole("button", { name: "Remove" }));
    await settle();
    expect(auth.removeMember).not.toHaveBeenCalled();

    fireEvent.change(within(row("u-dev@acme.com")).getByRole("combobox"), { target: { value: "viewer" } });
    expect(await screen.findByText("Could not change role")).toBeTruthy();
    expect(screen.getByText("AUTH.FORBIDDEN: last owner")).toBeTruthy();

    confirmReply = true;
    fireEvent.click(within(row("u-dev@acme.com")).getByRole("button", { name: "Remove" }));
    expect(await screen.findByText("Could not remove member")).toBeTruthy();
  });
});

describe("as an admin", () => {
  it("manages non-owners only, and cannot grant the owner role anywhere", async () => {
    auth.listMembers.mockResolvedValue(roster("admin"));
    renderRoute(<Members />);
    await screen.findAllByText("u-dev@acme.com");

    expect(within(row("u-owner2@acme.com")).queryByRole("combobox")).toBeNull();
    expect(within(row("u-owner2@acme.com")).getByText("Owner")).toBeTruthy();
    expect(within(row("u-owner2@acme.com")).queryByRole("button", { name: "Remove" })).toBeNull();
    const devSelect = within(row("u-dev@acme.com")).getByRole("combobox");
    expect((within(devSelect).getByRole("option", { name: "Owner" }) as HTMLOptionElement).disabled).toBe(true);
    expect(within(row("u-me@acme.com")).getByText("Admin · you")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Invite" }));
    const dialog = screen.getByRole("dialog");
    expect((within(dialog).getByRole("option", { name: "Owner" }) as HTMLOptionElement).disabled).toBe(true);
  });
});

describe("as a member", () => {
  it("sees no management controls and reads no invitations or policy, but can leave", async () => {
    auth.listMembers.mockResolvedValue(roster("member"));
    renderRoute(<Members />);
    await screen.findAllByText("u-dev@acme.com");

    expect(screen.queryByRole("button", { name: "Invite" })).toBeNull();
    expect(screen.queryAllByRole("combobox")).toHaveLength(0);
    expect(screen.queryByText("Sign-up policy")).toBeNull();
    expect(auth.listInvitations).not.toHaveBeenCalled();
    expect(auth.getSignupPolicy).not.toHaveBeenCalled();

    confirmReply = false;
    fireEvent.click(screen.getByRole("button", { name: "Leave organization" }));
    await settle();
    expect(auth.removeMember).not.toHaveBeenCalled();
    confirmReply = true;
    fireEvent.click(screen.getByRole("button", { name: "Leave organization" }));
    await waitFor(() => expect(auth.removeMember).toHaveBeenCalledWith("acme", "u-me"));
  });

  it("says the list did not load rather than implying the org is empty", async () => {
    auth.listMembers.mockResolvedValue([]);
    renderRoute(<Members />);
    expect(await screen.findByText("Members did not load")).toBeTruthy();
    expect(screen.getByText("Reload the page to try again.")).toBeTruthy();
    expect(screen.queryByText("No members yet")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Leave organization" }));
    expect(auth.removeMember).not.toHaveBeenCalled();
  });

  it("shows why a failed roster read failed", async () => {
    auth.listMembers.mockRejectedValue(new ApiError(503, { code: "UNAVAILABLE", message: "members unavailable" }));
    renderRoute(<Members />);

    expect((await screen.findByRole("alert")).textContent).toBe("UNAVAILABLE: members unavailable");
    expect(screen.queryByText("Members did not load")).toBeNull();
  });
});

describe("inviting", () => {
  it("sends the address and chosen role, and names the outcome", async () => {
    auth.addMember.mockResolvedValueOnce({ status: "invited", member: null, invitation: null }).mockResolvedValueOnce({ status: "added", member: null, invitation: null });
    renderRoute(<Members />);
    fireEvent.click(await screen.findByRole("button", { name: "Invite" }));

    const dialog = screen.getByRole("dialog");
    const send = within(dialog).getByRole("button", { name: "Send invitation" }) as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Email" }), { target: { value: "new@acme.com" } });
    fireEvent.change(within(dialog).getByRole("combobox", { name: "Role" }), { target: { value: "viewer" } });
    fireEvent.click(send);

    await waitFor(() => expect(auth.addMember).toHaveBeenCalledWith("acme", { email: "new@acme.com", role: "viewer" }));
    expect(await screen.findByText("Invitation sent")).toBeTruthy();
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Invite" }));
    const again = screen.getByRole("dialog");
    expect((within(again).getByRole("textbox", { name: "Email" }) as HTMLInputElement).value).toBe("");
    fireEvent.change(within(again).getByRole("textbox", { name: "Email" }), { target: { value: "joined@acme.com" } });
    fireEvent.submit(within(again).getByRole("textbox", { name: "Email" }).closest("form")!);
    await waitFor(() => expect(auth.addMember).toHaveBeenLastCalledWith("acme", { email: "joined@acme.com", role: "member" }));
    expect(await screen.findByText("Member added")).toBeTruthy();
  });

  it("keeps the dialog open with the reason when the invite is refused", async () => {
    auth.addMember.mockRejectedValue(new ApiError(422, { code: "ORG.INVALID_EMAIL", message: "not an address" }));
    renderRoute(<Members />);
    fireEvent.click(await screen.findByRole("button", { name: "Invite" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.submit(within(dialog).getByRole("textbox", { name: "Email" }).closest("form")!);
    expect(auth.addMember).not.toHaveBeenCalled();

    fireEvent.change(within(dialog).getByRole("textbox", { name: "Email" }), { target: { value: "x" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Send invitation" }));
    expect(await screen.findByText("Could not add member")).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();

    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("pending invitations", () => {
  it("lists each with its age and role, and revokes on confirmation", async () => {
    vi.useFakeTimers({ now: new Date("2026-09-25T12:00:00Z"), toFake: ["Date"] });
    const invite = (id: string, created_at: string): OrgInvitation => ({ id, email: `${id}@x.com`, role: "viewer", state: "pending", created_at });
    auth.listInvitations.mockResolvedValue([invite("i-today", "2026-09-25T08:00:00Z"), invite("i-one", "2026-09-24T12:00:00Z"), invite("i-old", "2026-09-22T12:00:00Z")]);
    auth.revokeInvitation.mockRejectedValueOnce(new Error("already accepted")).mockResolvedValue(null);
    try {
      renderRoute(<Members />);

      expect(await screen.findByText("invited today · Viewer")).toBeTruthy();
      expect(screen.getByText("invited 1d ago · Viewer")).toBeTruthy();
      expect(screen.getByText("invited 3d ago · Viewer")).toBeTruthy();

      const revoke = within(screen.getByText("i-old@x.com").parentElement!).getByRole("button", { name: "Revoke" });
      confirmReply = false;
      fireEvent.click(revoke);
      await settle();
      expect(auth.revokeInvitation).not.toHaveBeenCalled();
      confirmReply = true;
      fireEvent.click(revoke);
      expect(await screen.findByText("Could not revoke invitation")).toBeTruthy();
      fireEvent.click(revoke);
      await waitFor(() => expect(auth.revokeInvitation).toHaveBeenLastCalledWith("acme", "i-old"));
      expect(await screen.findByText("Invitation revoked")).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("the sign-up policy", () => {
  it("saves a domain list split and trimmed, and only once something changed", async () => {
    auth.getSignupPolicy.mockResolvedValue({ ...POLICY, mode: "domain", domains: ["acme.com"] });
    renderRoute(<Members />);

    const save = (await screen.findByRole("button", { name: "Save policy" })) as HTMLButtonElement;
    await waitFor(() => expect((screen.getByRole("textbox", { name: "Email domains" }) as HTMLInputElement).value).toBe("acme.com"));
    expect(save.disabled).toBe(true);

    fireEvent.change(screen.getByRole("textbox", { name: "Email domains" }), { target: { value: " acme.com,  acme.io  beta.dev," } });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);

    await waitFor(() => expect(auth.updateSignupPolicy).toHaveBeenCalledWith("acme", { mode: "domain", domains: ["acme.com", "acme.io", "beta.dev"] }));
    expect(await screen.findByText("Sign-up policy saved")).toBeTruthy();
  });

  it("sends no domains outside domain mode, and reports a refused save", async () => {
    auth.updateSignupPolicy.mockRejectedValue(new Error("nope"));
    renderRoute(<Members />);
    fireEvent.click(await screen.findByRole("radio", { name: /Invitation only/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save policy" }));

    await waitFor(() => expect(auth.updateSignupPolicy).toHaveBeenCalledWith("acme", { mode: "invite", domains: [] }));
    expect(await screen.findByText("Could not save the sign-up policy")).toBeTruthy();
  });

  it("is read-only through an organization that does not govern it, and names the one that does", async () => {
    auth.getSignupPolicy.mockResolvedValue({ ...POLICY, governing: false, governing_org_slug: "first-org" });
    renderRoute(<Members />);

    expect(await screen.findByRole("note")).toBeTruthy();
    expect(screen.getByText("first-org")).toBeTruthy();
    for (const radio of screen.getAllByRole("radio")) expect((radio as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Save policy" }) as HTMLButtonElement).disabled).toBe(true);
    cleanup();

    auth.getSignupPolicy.mockResolvedValue({ ...POLICY, governing: false, governing_org_slug: null });
    renderRoute(<Members />);
    expect(await screen.findByText("unknown")).toBeTruthy();
  });
});

describe("while loading", () => {
  it("says so", async () => {
    auth.listMembers.mockImplementation(pending);
    renderRoute(<Members />);
    expect(await screen.findByText("Loading members…")).toBeTruthy();
  });
});
