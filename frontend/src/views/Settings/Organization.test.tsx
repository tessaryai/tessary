// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → Organization. The controls mirror the server's owner-only rule, and the default project
 * can never be archived or deleted. The bugs worth catching: a non-owner offered a control that will
 * only be refused, a lifecycle action on the default or a deleting project, an action sent for the
 * wrong project, a delete without its confirmation, and a deleting row that never goes away.
 */
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { OrgMember, OrgRole, Organization as Org, Project } from "../../api/types-auth";
import { renderRoute } from "../../test/render";
import { DensityProvider } from "../../ui/density";
import { Organization } from "./Organization";

const auth = vi.hoisted(() => ({
  getOrg: vi.fn(),
  listProjects: vi.fn(),
  listMembers: vi.fn(),
  updateOrg: vi.fn(),
  makeProjectDefault: vi.fn(),
  archiveProject: vi.fn(),
  unarchiveProject: vi.fn(),
  deleteProject: vi.fn(),
}));
const session = vi.hoisted(() => ({ role: "owner" as OrgRole }));

vi.mock("../../api/client", () => ({ auth }));
vi.mock("../../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "support" }) }));
vi.mock("../../auth/AuthContext", () => ({ useAuth: () => ({ user: { id: "u-me" } }) }));

const ORG: Org = {
  id: "org_0123",
  workos_org_id: null,
  slug: "acme",
  name: "Acme",
  created_at: "2026-01-01T00:00:00Z",
  archived_at: null,
  settings: null,
};
const project = (slug: string, over: Partial<Project> = {}): Project => ({
  id: `p-${slug}`,
  org_id: "org_0123",
  slug,
  name: slug,
  description: null,
  created_at: "2026-01-01T00:00:00Z",
  archived_at: null,
  settings: null,
  is_default: false,
  deleting_at: null,
  ...over,
});
const member = (user_id: string, role: OrgRole): OrgMember => ({
  user_id,
  email: `${user_id}@acme.com`,
  display_name: null,
  avatar_url: null,
  role,
  created_at: "2026-01-01T00:00:00Z",
});
const PROJECTS = [
  project("default", { is_default: true }),
  project("support"),
  project("old", { archived_at: "2026-06-01T00:00:00Z" }),
  project("gone", { deleting_at: "2026-09-01T00:00:00Z" }),
];

let confirmReply = true;
beforeEach(() => {
  session.role = "owner";
  confirmReply = true;
  vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);
  auth.getOrg.mockResolvedValue(ORG);
  auth.listProjects.mockResolvedValue(PROJECTS.filter((p) => !p.deleting_at));
  auth.listMembers.mockImplementation(async () => [member("u-other", "owner"), member("u-me", session.role)]);
  for (const f of [auth.updateOrg, auth.makeProjectDefault, auth.archiveProject, auth.unarchiveProject, auth.deleteProject]) {
    f.mockResolvedValue({});
  }
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  window.localStorage.clear();
});

const row = (slug: string) => screen.getByText(slug, { selector: "span.font-mono" }).closest("tr")!;
const buttonsIn = (slug: string) => within(row(slug)).queryAllByRole("button").map((b) => b.textContent);
const nameInput = () => screen.getByLabelText("Organization name") as HTMLInputElement;
const renameButton = () => screen.getByRole("button", { name: "Rename" }) as HTMLButtonElement;

describe("who can change what", () => {
  it("gives an owner each lifecycle action a project can take, and none on the default", async () => {
    auth.listProjects.mockResolvedValue(PROJECTS);
    renderRoute(<Organization />);
    await screen.findByText("gone");
    await waitFor(() => expect(nameInput().disabled).toBe(false));

    expect(buttonsIn("default")).toEqual([]);
    expect(within(row("default")).getByText("default", { selector: ".ml-2" })).toBeTruthy();
    expect(buttonsIn("support")).toEqual(["Make default", "Archive", "Delete project"]);
    expect(within(row("support")).getByText("(current)")).toBeTruthy();
    expect(screen.getAllByText("(current)")).toHaveLength(1);
    expect(buttonsIn("old")).toEqual(["Unarchive", "Delete project"]);
    expect(within(row("old")).getByText("archived")).toBeTruthy();
    expect(buttonsIn("gone")).toEqual([]);
    expect(within(row("gone")).getByText("deleting…")).toBeTruthy();
    expect(screen.getByText("4 projects")).toBeTruthy();
  });

  it("gives an admin, who is not an owner, a read-only page", async () => {
    session.role = "admin";
    renderRoute(<Organization />);
    await screen.findByText("old");
    await waitFor(() => expect(nameInput().value).toBe("Acme"));

    expect(nameInput().disabled).toBe(true);
    fireEvent.change(nameInput(), { target: { value: "Acme Corp" } });
    expect(renameButton().disabled).toBe(true);
    for (const slug of ["default", "support", "old"]) expect(buttonsIn(slug)).toEqual([]);
  });
});

describe("renaming", () => {
  it("renames to the trimmed name, and only once it differs", async () => {
    renderRoute(<Organization />);
    await waitFor(() => expect(nameInput().value).toBe("Acme"));
    await waitFor(() => expect(nameInput().disabled).toBe(false));

    expect(renameButton().disabled).toBe(true);
    fireEvent.change(nameInput(), { target: { value: " Acme " } });
    expect(renameButton().disabled).toBe(true);
    fireEvent.change(nameInput(), { target: { value: "   " } });
    expect(renameButton().disabled).toBe(true);
    fireEvent.change(nameInput(), { target: { value: " Acme Corp " } });
    fireEvent.click(renameButton());

    await waitFor(() => expect(auth.updateOrg).toHaveBeenCalledWith("acme", { name: "Acme Corp" }));
    expect(await screen.findByText("Organization renamed")).toBeTruthy();
  });

  it("says why a rename was refused", async () => {
    auth.updateOrg.mockRejectedValue(new Error("name in use"));
    renderRoute(<Organization />);
    await waitFor(() => expect(nameInput().disabled).toBe(false));

    fireEvent.change(nameInput(), { target: { value: "Globex" } });
    fireEvent.click(renameButton());

    expect(await screen.findByText("Could not rename organization")).toBeTruthy();
    expect(screen.getByText("name in use")).toBeTruthy();
  });
});

describe("project lifecycle", () => {
  const ready = async () => {
    renderRoute(<Organization />);
    await waitFor(() => expect(buttonsIn("support")).toContain("Archive"));
  };
  const click = (slug: string, name: string) => fireEvent.click(within(row(slug)).getByRole("button", { name }));

  it("makes, archives, and unarchives the project whose row was used", async () => {
    await ready();

    click("support", "Make default");
    expect(await screen.findByText("Default project updated")).toBeTruthy();
    click("support", "Archive");
    expect(await screen.findByText("Project archived")).toBeTruthy();
    click("old", "Unarchive");
    expect(await screen.findByText("Project unarchived")).toBeTruthy();

    expect(auth.makeProjectDefault).toHaveBeenCalledWith("acme", "support");
    expect(auth.archiveProject).toHaveBeenCalledWith("acme", "support");
    expect(auth.unarchiveProject).toHaveBeenCalledWith("acme", "old");
    expect(auth.archiveProject).toHaveBeenCalledTimes(1);
  });

  it("deletes only after confirmation, and says the data goes in the background", async () => {
    await ready();

    confirmReply = false;
    click("old", "Delete project");
    expect(auth.deleteProject).not.toHaveBeenCalled();

    confirmReply = true;
    click("old", "Delete project");
    expect(await screen.findByText("Project deletion started")).toBeTruthy();
    expect(auth.deleteProject).toHaveBeenCalledWith("acme", "old");
  });

  it("names each refused action", async () => {
    auth.makeProjectDefault.mockRejectedValue(new Error("x"));
    auth.archiveProject.mockRejectedValue(new Error("x"));
    auth.unarchiveProject.mockRejectedValue(new Error("x"));
    auth.deleteProject.mockRejectedValue(new Error("x"));
    await ready();

    click("support", "Make default");
    expect(await screen.findByText("Could not set default")).toBeTruthy();
    click("support", "Archive");
    expect(await screen.findByText("Could not archive project")).toBeTruthy();
    click("old", "Unarchive");
    expect(await screen.findByText("Could not unarchive project")).toBeTruthy();
    click("old", "Delete project");
    expect(await screen.findByText("Could not delete project")).toBeTruthy();
  });

  it("re-reads the list while a project is deleting, so its row goes when the data does", async () => {
    auth.listProjects
      .mockResolvedValueOnce([project("default", { is_default: true }), project("gone", { deleting_at: "2026-09-01T00:00:00Z" })])
      .mockResolvedValue([project("default", { is_default: true })]);
    renderRoute(<Organization />);
    await screen.findByText("gone");

    await waitFor(() => expect(screen.queryByText("gone")).toBeNull(), { timeout: 4500 });
    expect(screen.getByText("1 project")).toBeTruthy();
  });

  it("says when there are no projects", async () => {
    auth.listProjects.mockResolvedValue([]);
    renderRoute(<Organization />);

    expect(await screen.findByText("No projects yet")).toBeTruthy();
    expect(screen.getByText("0 projects")).toBeTruthy();
  });
});

describe("the organization ID and appearance", () => {
  it("copies the ID once it has loaded, and says to copy by hand when the clipboard refuses", async () => {
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValue(new Error("denied"));
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    renderRoute(<Organization />);
    const copy = await screen.findByRole("button", { name: "Copy" });
    await waitFor(() => expect((copy as HTMLButtonElement).disabled).toBe(false));

    fireEvent.click(copy);
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("org_0123"));
    fireEvent.click(await screen.findByRole("button", { name: "Copied" }));
    expect(await screen.findByText("Copy the organization ID manually.")).toBeTruthy();
  });

  it("switches density app-wide and remembers it", async () => {
    const { container } = renderRoute(
      <DensityProvider>
        <Organization />
      </DensityProvider>,
    );
    const compact = screen.getByRole("radio", { name: "Compact" });
    expect(compact.getAttribute("aria-checked")).toBe("false");

    fireEvent.click(compact);

    expect(compact.getAttribute("aria-checked")).toBe("true");
    expect(container.querySelector("[data-density]")!.getAttribute("data-density")).toBe("compact");
    expect(window.localStorage.getItem("tessary.prefs.density")).toBe("compact");
    cleanup();

    renderRoute(
      <DensityProvider>
        <Organization />
      </DensityProvider>,
    );
    expect(screen.getByRole("radio", { name: "Compact" }).getAttribute("aria-checked")).toBe("true");
  });
});
