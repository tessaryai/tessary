// SPDX-License-Identifier: Apache-2.0
/*
 * New project. The bugs worth catching: a project created in another org, a name or description sent
 * with its stray whitespace (or an empty description sent as ""), and a refusal that says nothing.
 */
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { currentLocation, renderRoute } from "../test/render";
import { NewProject } from "./NewProject";

const auth = vi.hoisted(() => ({ createProject: vi.fn() }));
vi.mock("../api/client", () => ({ auth }));

beforeEach(() => {
  auth.createProject.mockResolvedValue({ slug: "support-agent" });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const open = () => renderRoute(<NewProject />, { route: "/orgs/acme/new-project", path: "/orgs/:orgSlug/new-project" });
const create = () => screen.getByRole("button", { name: "Create project" }) as HTMLButtonElement;

describe("NewProject", () => {
  it("creates the project in this org, trimmed, and opens its Triage", async () => {
    open();
    expect(create().disabled).toBe(true);

    fireEvent.change(screen.getByLabelText("Project name", { exact: false }), { target: { value: "  Support agent " } });
    fireEvent.change(screen.getByLabelText("Description"), { target: { value: " Answers refunds " } });
    fireEvent.click(create());

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/support-agent/triage"));
    expect(auth.createProject).toHaveBeenCalledWith("acme", { name: "Support agent", description: "Answers refunds" });
  });

  it("sends no description rather than a blank one", async () => {
    open();

    fireEvent.change(screen.getByLabelText("Project name", { exact: false }), { target: { value: "Ops" } });
    fireEvent.change(screen.getByLabelText("Description"), { target: { value: "   " } });
    fireEvent.click(create());

    await waitFor(() => expect(auth.createProject).toHaveBeenCalledWith("acme", { name: "Ops", description: null }));
  });

  it("says why a project was refused", async () => {
    auth.createProject.mockRejectedValue(new Error("A project with that name exists"));
    open();

    fireEvent.change(screen.getByLabelText("Project name", { exact: false }), { target: { value: "Ops" } });
    fireEvent.click(create());

    expect(await screen.findByText("A project with that name exists")).toBeTruthy();
    expect(currentLocation()).toBe("/orgs/acme/new-project");
  });
});
