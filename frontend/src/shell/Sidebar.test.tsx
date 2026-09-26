// SPDX-License-Identifier: Apache-2.0
/*
 * The sidebar. The bugs worth catching: a row that navigates to the wrong project's page or marks the
 * wrong row active, the open-case badge lost when the sidebar collapses, a collapse that is not
 * remembered, a project switcher that navigates when the current project is picked or cannot be
 * dismissed, and a sign-out that never reaches the server.
 */
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../test/render";
import { SETTINGS_ICON } from "./nav";
import { Sidebar } from "./Sidebar";

const { authApi, palette, shell, cases } = vi.hoisted(() => ({
  authApi: { listProjects: vi.fn(), logout: vi.fn() },
  palette: { open: vi.fn() },
  shell: { opener: null as null | (() => void) },
  cases: { open: 3 },
}));

vi.mock("../api/client", () => ({ auth: authApi }));
vi.mock("../tenant/TenantContext", () => ({ useTenant: () => ({ orgSlug: "acme", projectSlug: "default" }) }));
vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({
    user: { email: "dana@example.com", orgs: [{ id: "o1", slug: "acme", name: "Acme Inc", role: "owner" }] },
  }),
}));
vi.mock("./PaletteContext", () => ({ usePalette: () => palette }));
vi.mock("./useCases", () => ({ useCaseCounts: () => cases }));
vi.mock("./ShellActions", () => ({
  useShellActions: () => ({ registerProjectSwitcher: (fn: () => void) => (shell.opener = fn) }),
}));
vi.mock("./useNavigation", async () => {
  const { SETTINGS_ICON: icon } = await import("./nav");
  return {
    useNavigation: () => ({
      triage: { id: "triage", label: "Triage", icon },
      groups: [
        {
          label: "Monitor",
          items: [
            { id: "traces", label: "Traces", icon, match: ["sessions"] },
            { id: "vitals", label: "Vitals", icon },
          ],
        },
      ],
    }),
  };
});

const KEY = "tsy-sidebar-collapsed";
const project = (slug: string, name: string) => ({ id: `p-${slug}`, slug, name });

beforeEach(() => {
  cases.open = 3;
  authApi.listProjects.mockResolvedValue([project("default", "Default"), project("support", "Support bot")]);
  authApi.logout.mockResolvedValue({ frontendUrl: "https://tessary.example/" });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.restoreAllMocks();
  window.localStorage.clear();
});

const open = (route = "/orgs/acme/projects/default/triage") => renderRoute(<Sidebar />, { route });
const row = (label: string) => screen.getByText(label, { selector: "span" }).closest("button")!;
const isActive = (label: string) => row(label).className.includes("bg-selected");
void SETTINGS_ICON;

describe("the nav rows", () => {
  it("marks the page's own row active, counting a row's other paths as its own", () => {
    open("/orgs/acme/projects/default/sessions/s-1");

    expect(isActive("Traces")).toBe(true);
    expect(isActive("Triage")).toBe(false);
    expect(isActive("Vitals")).toBe(false);
    expect(screen.getByRole("group", { name: "Monitor" })).toBeTruthy();
  });

  it("marks a row active on its page and the pages under it", () => {
    open("/orgs/acme/projects/default/vitals/detail");
    expect(isActive("Vitals")).toBe(true);
    cleanup();

    open("/orgs/acme/projects/default/vitals");
    expect(isActive("Vitals")).toBe(true);
    expect(isActive("Settings")).toBe(false);
  });

  it("goes to this project's page for a row, and to Triage from the brand", () => {
    open("/orgs/acme/projects/default/vitals");

    fireEvent.click(row("Traces"));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/traces");
    fireEvent.click(screen.getByRole("button", { name: "Go to Triage" }));
    expect(currentLocation()).toBe("/orgs/acme/projects/default/triage");
  });

  it("counts open cases on Triage only, and not at all when there are none", () => {
    open();
    expect(screen.getByLabelText("3 open cases").textContent).toBe("3");
    cleanup();

    cases.open = 0;
    open();
    expect(screen.queryByLabelText(/open cases/)).toBeNull();
  });

  it("opens search from its button", () => {
    open();

    fireEvent.click(screen.getByRole("button", { name: /Search/ }));

    expect(palette.open).toHaveBeenCalled();
  });
});

describe("collapsing", () => {
  it("collapses to icons, keeps the open-case signal as a dot, and remembers it", () => {
    open();

    fireEvent.click(screen.getByRole("button", { name: "Collapse sidebar" }));

    expect(window.localStorage.getItem(KEY)).toBe("1");
    expect(screen.queryByLabelText("3 open cases")).toBeNull();
    expect(row("Triage").querySelector(".bg-error")).toBeTruthy();
    expect(row("Triage").getAttribute("title")).toBe("Triage");
    expect(screen.queryByText("Monitor")).toBeNull();
    cleanup();

    open();
    fireEvent.click(screen.getByRole("button", { name: "Expand sidebar" }));
    expect(window.localStorage.getItem(KEY)).toBe("0");
    expect(screen.getByLabelText("3 open cases")).toBeTruthy();
  });

  it("works on, unremembered, when storage refuses", () => {
    vi.spyOn(window.localStorage, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    open();

    fireEvent.click(screen.getByRole("button", { name: "Collapse sidebar" }));

    expect(screen.getByRole("button", { name: "Expand sidebar" })).toBeTruthy();
  });
});

describe("the project switcher", () => {
  const trigger = () => document.querySelector<HTMLElement>('[aria-haspopup="menu"]')!;

  it("names the current project and org, and lists the org's projects on hover", async () => {
    open();
    await waitFor(() => expect(trigger().textContent).toContain("Acme Inc"));
    expect(authApi.listProjects).toHaveBeenCalledWith("acme");

    fireEvent.mouseEnter(trigger().parentElement!);
    expect(screen.getByText("Projects")).toBeTruthy();
    fireEvent.mouseLeave(trigger().parentElement!);
    expect(screen.queryByText("Projects")).toBeNull();
  });

  it("goes to another project's Triage, but only closes on the current one", async () => {
    open("/orgs/acme/projects/default/vitals");
    await waitFor(() => expect(trigger().textContent).toContain("Default"));

    fireEvent.click(trigger());
    const menu = screen.getByText("Projects").parentElement!;
    fireEvent.click(within(menu).getByRole("button", { name: "Default" }));
    expect(screen.queryByText("Projects")).toBeNull();
    expect(currentLocation()).toBe("/orgs/acme/projects/default/vitals");

    fireEvent.focus(trigger());
    fireEvent.click(within(screen.getByText("Projects").parentElement!).getByRole("button", { name: "Support bot" }));
    expect(currentLocation()).toBe("/orgs/acme/projects/support/triage");
    expect(screen.queryByText("Projects")).toBeNull();
  });

  it("starts a new project in this org", async () => {
    open();
    await waitFor(() => expect(trigger().textContent).toContain("Default"));

    fireEvent.click(trigger());
    fireEvent.click(screen.getByRole("button", { name: "+ New project" }));

    expect(currentLocation()).toBe("/orgs/acme/new-project");
  });

  it("opens from the shell's shortcut, and closes on an outside click or ESC but not an inside one", async () => {
    open();
    await waitFor(() => expect(shell.opener).not.toBeNull());

    act(() => shell.opener!());
    const menu = screen.getByText("Projects");
    fireEvent.mouseDown(menu);
    expect(screen.getByText("Projects")).toBeTruthy();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(screen.getByText("Projects")).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByText("Projects")).toBeNull();

    act(() => shell.opener!());
    fireEvent.mouseDown(document.body);
    expect(screen.queryByText("Projects")).toBeNull();
  });

  it("shows the project's initial when collapsed, and the slug before the list has loaded", async () => {
    authApi.listProjects.mockReturnValue(new Promise(() => {}));
    window.localStorage.setItem(KEY, "1");
    open();

    expect(screen.getByTitle("Acme Inc / default").textContent).toBe("D");
  });
});

describe("the account menu", () => {
  it("shows who is signed in, and signs out through the server", async () => {
    open();
    const account = screen.getByRole("button", { name: /dana@example.com/ });
    expect(account.textContent).toContain("D");

    fireEvent.click(account);
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(authApi.logout).toHaveBeenCalled());

    fireEvent.click(account);
    expect(screen.queryByRole("button", { name: "Sign out" })).toBeNull();
  });

  it("still leaves when the server's sign-out fails", async () => {
    authApi.logout.mockRejectedValue(new Error("offline"));
    open();

    fireEvent.click(screen.getByRole("button", { name: /dana@example.com/ }));
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => expect(authApi.logout).toHaveBeenCalled());
  });
});
