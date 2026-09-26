// SPDX-License-Identifier: Apache-2.0
/*
 * The device-link screen: confirming issues a token scoped to one project, so the bugs worth catching
 * are a confirm that names a different org or project than the one on screen, a Decline that confirms,
 * a confirm offered with no project chosen, and a link past its life (missing, expired, declined,
 * claimed elsewhere) that still offers to connect.
 */
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Project } from "../api/types-auth";
import { currentLocation, renderRoute } from "../test/render";
import { Link } from "./Link";

const { auth, link, getPipeline, projectApi } = vi.hoisted(() => {
  const getPipeline = vi.fn();
  return {
    auth: { listProjects: vi.fn(), createProject: vi.fn() },
    link: { get: vi.fn(), confirm: vi.fn(), deny: vi.fn() },
    getPipeline,
    projectApi: vi.fn((org: string, project: string) => ({ base: `/api/orgs/${org}/projects/${project}`, getPipeline })),
  };
});

vi.mock("../api/client", () => ({ auth, link, projectApi }));
vi.mock("../auth/AuthContext", () => ({
  useAuth: () => ({
    user: {
      orgs: [
        { id: "o1", slug: "acme", name: "Acme", role: "owner" },
        { id: "o2", slug: "globex", name: "Globex", role: "member" },
      ],
    },
  }),
}));

const project = (slug: string): Project =>
  ({ id: `p-${slug}`, slug, name: slug.replace("-", " ") }) as Project;
const PENDING = { userCode: "WXYZ-1234", clientLabel: "Claude Code on dana-mbp", status: "pending", expiresAt: "" };
const pipeline = (callSites: number) => ({ ok: true, pipeline: { call_sites: Array.from({ length: callSites }, () => ({})) } });

beforeEach(() => {
  link.get.mockResolvedValue(PENDING);
  link.confirm.mockResolvedValue({ status: "claimed" });
  link.deny.mockResolvedValue({ status: "denied" });
  auth.listProjects.mockImplementation(async (org: string) =>
    org === "acme" ? [project("default"), project("support-bot")] : [],
  );
  getPipeline.mockResolvedValue(pipeline(0));
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.useRealTimers();
});

let queryClient: ReturnType<typeof renderRoute>["queryClient"];
const open = (code = "WXYZ-1234") => ({ queryClient } = renderRoute(<Link />, { route: `/link?code=${code}` }));
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const select = (label: string) => screen.getByLabelText(label, { exact: false }) as HTMLSelectElement;
const ready = async () => {
  open();
  await screen.findByRole("heading", { name: "Claude Code on dana-mbp wants to connect" });
  await waitFor(() => expect(select("Project").value).toBe("default"));
};

describe("a link that cannot be confirmed", () => {
  it("says a link without a code is invalid, without reading anything", () => {
    renderRoute(<Link />, { route: "/link" });

    expect(screen.getByRole("heading", { name: "Invalid link" })).toBeTruthy();
    expect(link.get).not.toHaveBeenCalled();
  });

  it.each([
    ["not found", () => link.get.mockRejectedValue(new Error("404")), "Link not found"],
    ["expired", () => link.get.mockResolvedValue({ ...PENDING, status: "expired" }), "Link expired"],
    ["declined", () => link.get.mockResolvedValue({ ...PENDING, status: "denied" }), "Link declined"],
  ])("says a %s link is dead and offers no Connect", async (_what, arrange, title) => {
    arrange();
    open();

    expect(await screen.findByRole("heading", { name: title })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Connect" })).toBeNull();
  });

  it("offers a way into the project for a link claimed elsewhere, and never confirms it again", async () => {
    link.get.mockResolvedValue({ ...PENDING, status: "claimed" });
    open();

    fireEvent.click(await screen.findByRole("button", { name: "Open the project" }));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/triage");
    expect(link.confirm).not.toHaveBeenCalled();
  });
});

describe("confirming", () => {
  it("names the session and its code, and connects it to the org and project on screen", async () => {
    await ready();
    expect(screen.getByText("WXYZ-1234")).toBeTruthy();

    fireEvent.change(select("Project"), { target: { value: "support-bot" } });
    fireEvent.click(button("Connect"));

    await waitFor(() =>
      expect(link.confirm).toHaveBeenCalledWith("WXYZ-1234", { org_slug: "acme", project_slug: "support-bot" }),
    );
    expect(await screen.findByRole("heading", { name: "Connected" })).toBeTruthy();
    expect(link.deny).not.toHaveBeenCalled();
  });

  it("calls an unlabelled session a Claude Code session", async () => {
    link.get.mockResolvedValue({ ...PENDING, clientLabel: null });
    open();

    expect(await screen.findByRole("heading", { name: "A Claude Code session wants to connect" })).toBeTruthy();
  });

  it("holds Connect while the chosen org has no project", async () => {
    await ready();

    fireEvent.change(select("Organization"), { target: { value: "globex" } });

    await waitFor(() => expect(auth.listProjects).toHaveBeenCalledWith("globex"));
    expect(await screen.findByRole("option", { name: "No projects yet" })).toBeTruthy();
    expect(button("Connect").disabled).toBe(true);
  });

  it("moves off a project that disappears from a re-read list, rather than connecting to it", async () => {
    open();
    await waitFor(() => expect(select("Project").value).toBe("default"));
    fireEvent.change(select("Project"), { target: { value: "support-bot" } });

    auth.listProjects.mockResolvedValue([project("default"), project("ops")]);
    await act(() => queryClient.invalidateQueries({ queryKey: ["projects", "acme"] }));
    await waitFor(() => expect(select("Project").value).toBe("default"));
    await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
    fireEvent.click(button("Connect"));

    await waitFor(() =>
      expect(link.confirm).toHaveBeenCalledWith("WXYZ-1234", { org_slug: "acme", project_slug: "default" }),
    );
  });

  it("opens the project once its graders are published", async () => {
    getPipeline.mockResolvedValueOnce(pipeline(0)).mockResolvedValue(pipeline(2));
    await ready();

    fireEvent.click(button("Connect"));
    await waitFor(() => expect(getPipeline).toHaveBeenCalledTimes(1));
    expect(projectApi).toHaveBeenCalledWith("acme", "default");
    expect(currentLocation()).toBe("/link?code=WXYZ-1234");

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/overview"), { timeout: 3500 });
  });

  it("opens the project anyway if the graders never arrive", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await ready();

    fireEvent.click(button("Connect"));
    await screen.findByRole("heading", { name: "Connected" });
    await act(() => vi.advanceTimersByTimeAsync(44_000));
    expect(currentLocation()).toBe("/link?code=WXYZ-1234");
    await act(() => vi.advanceTimersByTimeAsync(1_000));

    expect(currentLocation()).toBe("/orgs/acme/projects/default/overview");
  });

  it("says why a confirm was refused and stays on the form", async () => {
    link.confirm.mockRejectedValue(new Error("link already used"));
    await ready();

    fireEvent.click(button("Connect"));

    expect(await screen.findByText("link already used")).toBeTruthy();
    expect(button("Connect")).toBeTruthy();
  });

  it("declines without confirming", async () => {
    await ready();

    fireEvent.click(button("Decline"));

    expect(await screen.findByRole("heading", { name: "Link declined" })).toBeTruthy();
    expect(link.deny).toHaveBeenCalledWith("WXYZ-1234");
    expect(link.confirm).not.toHaveBeenCalled();
  });
});

describe("creating a project to connect to", () => {
  it("creates it under the trimmed name, selects it, and holds Connect while naming", async () => {
    auth.createProject.mockImplementation(async () => {
      auth.listProjects.mockResolvedValue([project("default"), project("support-bot"), project("new-agent")]);
      return project("new-agent");
    });
    await ready();

    fireEvent.click(button("New project"));
    expect(button("Connect").disabled).toBe(true);
    expect(button("Create").disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("New project name", { exact: false }), { target: { value: "  New agent " } });
    fireEvent.click(button("Create"));

    await waitFor(() => expect(select("Project").value).toBe("new-agent"));
    expect(auth.createProject).toHaveBeenCalledWith("acme", { name: "New agent" });
    expect(button("Connect").disabled).toBe(false);
  });
});
