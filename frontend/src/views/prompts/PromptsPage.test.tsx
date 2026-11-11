// SPDX-License-Identifier: Apache-2.0
/*
 * The Prompts list. The bugs worth catching are calls joined to the wrong prompt, folders that do not open or
 * cannot be left, filters that change the URL but not the read, a capped list that reads as complete, an empty
 * state that offers the wrong way out, and a folder delete that leaves the reader inside the folder it removed.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptSummary } from "../../api/types";
import { api } from "../../test/api";
import { promptSummary } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { currentLocation, currentParams, pending, renderRoute } from "../../test/render";
import { PromptsPage } from "./PromptsPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const GREETING = promptSummary({
  id: "p-1", name: "support/greeting", type: "chat", latest_version: 4,
  labels: [{ label: "production", version: 4 }, { label: "staging", version: 3 }],
});
const REFUND = promptSummary({ id: "p-2", name: "support/refund", type: "text", latest_version: 2, labels: [] });
const NESTED = promptSummary({ id: "p-3", name: "team/a/x", labels: [{ label: "canary", version: 1 }] });
const INTRO = promptSummary({ id: "p-4", name: "intro" });
const ALL = [GREETING, REFUND, NESTED, INTRO];

function list(items: PromptSummary[] = ALL, total = items.length) {
  api.listPrompts.mockResolvedValue({ items, page: 1, limit: 200, total });
}
function usage(items = [{ name: "support/greeting", calls: 1204 }]) {
  api.getPromptUsageSummary.mockResolvedValue({ days: 7, items });
}

function renderPage(search = "") {
  return renderRoute(<PromptsPage />, {
    route: `/orgs/acme/projects/default/prompts${search}`,
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "prompts",
  }).queryClient;
}

const lastListCall = () => api.listPrompts.mock.calls.at(-1)![0];
const rowOf = (text: string) => screen.getByText(text).closest("tr")!;
const cellsOf = (text: string) => within(rowOf(text)).getAllByRole("cell").map((c) => c.textContent);

describe("rows", () => {
  // Bug: calls joined by leaf name or row index, so one prompt shows another's calls, or a prompt without calls shows a blank.
  it("shows each prompt's type, latest version, labels with versions and calls joined by full name", async () => {
    list([GREETING, REFUND]);
    usage();
    renderPage("?folder=support");

    await screen.findByRole("link", { name: "greeting" });
    expect(cellsOf("greeting").slice(0, 5)).toEqual(["greeting", "chat", "v4", "production v4staging v3", "1,204"]);
    expect(cellsOf("refund").slice(0, 5)).toEqual(["refund", "text", "v2", "No labels", "0"]);
    expect(screen.getByRole("link", { name: "greeting" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts/p-1");
    expect(lastListCall()).toEqual({ folder: "support", page: 1, limit: 200 });
  });

  // Bug: a folder row that only opens on a mouse click, or opens on any key.
  it.each([
    ["a click", (row: HTMLElement) => fireEvent.click(row)],
    ["Enter", (row: HTMLElement) => fireEvent.keyDown(row, { key: "Enter" })],
    ["Space", (row: HTMLElement) => fireEvent.keyDown(row, { key: " " })],
  ])("opens a folder on %s", async (_, act) => {
    list();
    usage();
    renderPage();

    await screen.findByText("team");
    expect(cellsOf("team")[0]).toBe("team (1)");
    act(rowOf("team"));

    await waitFor(() => expect(currentParams().get("folder")).toBe("team"));
  });

  it("ignores other keys on a folder row", async () => {
    list();
    usage();
    renderPage();

    await screen.findByText("team");
    fireEvent.keyDown(rowOf("team"), { key: "a" });

    expect(currentParams().get("folder")).toBeNull();
  });

  // Bug: a search shows leaf names, so two prompts called greeting in different folders look alike.
  it("lists full names, flat, while searching", async () => {
    list([GREETING, NESTED]);
    usage();
    renderPage("?q=greet");

    expect(await screen.findByRole("link", { name: "support/greeting" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "team/a/x" })).toBeTruthy();
    expect(lastListCall()).toMatchObject({ q: "greet" });
  });
});

describe("the breadcrumb", () => {
  // Bug: no way back to the root, or a crumb that links to its own segment instead of the path up to it.
  it("links each folder above the current one", async () => {
    list([NESTED]);
    usage();
    renderPage("?folder=team/a");

    const crumbs = await screen.findByRole("navigation", { name: "Breadcrumb" });
    expect(within(crumbs).getByRole("link", { name: "team" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts?folder=team");
    fireEvent.click(within(crumbs).getByRole("link", { name: "Prompts" }));
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts"));
  });

  it("has no breadcrumb at the root", async () => {
    list();
    usage();
    renderPage();

    await screen.findByText("team");
    expect(screen.queryByRole("navigation", { name: "Breadcrumb" })).toBeNull();
  });
});

describe("filters", () => {
  // Bug: a search per keystroke, untrimmed, or one that cannot be cleared.
  it("searches on Enter, trimmed, and clears on a blank Enter", async () => {
    list();
    usage();
    renderPage();
    const box = await screen.findByRole("textbox", { name: "Search prompts" });

    fireEvent.change(box, { target: { value: "  greet  " } });
    fireEvent.keyDown(box, { key: "a" });
    expect(currentParams().get("q")).toBeNull();
    fireEvent.keyDown(box, { key: "Enter" });
    await waitFor(() => expect(lastListCall()).toMatchObject({ q: "greet" }));
    expect(currentParams().get("q")).toBe("greet");

    const cleared = screen.getByRole("textbox", { name: "Search prompts" });
    fireEvent.change(cleared, { target: { value: " " } });
    fireEvent.keyDown(cleared, { key: "Enter" });
    await waitFor(() => expect(currentParams().get("q")).toBeNull());
  });

  // Bug: the type or label control writes the URL but the read ignores it.
  it("filters by type and by deployment label", async () => {
    list();
    usage();
    renderPage();
    await screen.findByText("team");

    fireEvent.click(screen.getByRole("button", { name: "Chat" }));
    await waitFor(() => expect(lastListCall()).toMatchObject({ type: "chat" }));
    fireEvent.click(screen.getByRole("button", { name: "All" }));
    await waitFor(() => expect(currentParams().get("type")).toBeNull());

    const label = screen.getByRole("combobox", { name: "Deployment label" }) as HTMLSelectElement;
    expect(Array.from(label.options).map((o) => o.textContent)).toEqual(["Any label", "production", "staging", "canary"]);
    fireEvent.change(label, { target: { value: "production" } });
    await waitFor(() => expect(lastListCall()).toMatchObject({ label: "production" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Deployment label" }), { target: { value: "" } });
    await waitFor(() => expect(currentParams().get("label")).toBeNull());
  });

  // Bug: a label from the URL that no loaded prompt carries leaves the picker showing "Any label".
  it("keeps a label from the URL in the picker", async () => {
    list([]);
    usage();
    renderPage("?label=beta");

    await screen.findByText("No prompts match these filters");
    expect((screen.getByRole("combobox", { name: "Deployment label" }) as HTMLSelectElement).value).toBe("beta");
  });
});

describe("states", () => {
  // Bug: a list capped at 200 reads as every prompt.
  it("says when the list is capped", async () => {
    list(ALL, 312);
    usage();
    renderPage();

    expect(await screen.findByText("Showing the first 4 of 312 prompts. Search to narrow the list.")).toBeTruthy();
  });

  it("does not claim a cap when every prompt is shown", async () => {
    list();
    usage();
    renderPage();

    await screen.findByText("team");
    expect(screen.queryByText(/Showing the first/)).toBeNull();
  });

  // Bug: a new project told to clear filters it never set.
  it("offers a new prompt when the project has none", async () => {
    list([]);
    usage([]);
    renderPage();

    expect(await screen.findByText("No prompts yet")).toBeTruthy();
    expect(screen.getByText("Create a prompt to start.")).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: "New prompt" })[1]);
    expect(screen.getByRole("dialog")).toBeTruthy();
  });

  // Bug: Clear filters also leaves the folder, or leaves the filter that emptied the list.
  it("clears the filters but keeps the folder when nothing matches", async () => {
    list([]);
    usage([]);
    renderPage("?folder=support&label=beta&q=x&type=chat");

    expect(await screen.findByText("No prompts match these filters")).toBeTruthy();
    expect(screen.getByText("Change or clear the filters to see more prompts.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts?folder=support"));
  });

  // Bug: a folder emptied by a delete showed "No prompts match these filters", and Clear filters kept the folder, so
  // the button did nothing.
  it.each([
    ["support", "Back to prompts", "/orgs/acme/projects/default/prompts"],
    ["team/a", "Back to team", "/orgs/acme/projects/default/prompts?folder=team"],
  ])("offers the way up from the empty folder %s", async (folder, button, to) => {
    list([]);
    usage([]);
    renderPage(`?folder=${encodeURIComponent(folder)}`);

    expect(await screen.findByText("No prompts in this folder")).toBeTruthy();
    expect(screen.getByText(`A prompt is in this folder when its name starts with ${folder}/.`)).toBeTruthy();
    expect(screen.queryByText("No prompts match these filters")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: button }));

    await waitFor(() => expect(currentLocation()).toBe(to));
  });

  // Bug: a slow or failed usage read shows 0 calls, which reads as "never called".
  it("shows calls as counting, then as unknown with the error", async () => {
    list([GREETING]);
    api.getPromptUsageSummary.mockReturnValue(pending());
    renderPage("?folder=support");

    expect((await screen.findByText("…")).getAttribute("title")).toBe("Counting calls");
  });

  it("shows unknown calls and the error when usage fails", async () => {
    list([GREETING]);
    api.getPromptUsageSummary.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: days"));
    renderPage("?folder=support");

    expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: days");
    expect(cellsOf("greeting")[4]).toBe("—");
  });

  it("shows a skeleton while the list loads", () => {
    api.listPrompts.mockReturnValue(pending());
    usage();
    renderPage();

    expect(screen.getByRole("status", { name: "Loading table" })).toBeTruthy();
  });

  it("shows the error when the list fails", async () => {
    api.listPrompts.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: type"));
    usage();
    renderPage();

    expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: type");
  });
});

describe("new prompt", () => {
  it("opens and closes the new-prompt form", async () => {
    list();
    usage();
    renderPage();
    await screen.findByText("team");

    fireEvent.click(screen.getByRole("button", { name: "New prompt" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("deleting a folder", () => {
  // Bug: Delete folder offered at the root, where it would mean every prompt.
  it("is offered only inside a folder", async () => {
    list();
    usage();
    renderPage();

    await screen.findByText("team");
    expect(screen.queryByRole("button", { name: "Delete folder" })).toBeNull();
  });

  // Bug: the reader left inside the folder they removed, a miscounted toast, or the list still cached.
  it.each([
    ["team/a", 3, "Deleted 3 prompts", "team"],
    ["support", 1, "Deleted 1 prompt", null],
  ])("deletes %s after confirming and moves up a level", async (folder, count, toast, parent) => {
    list([]);
    usage([]);
    api.deletePromptFolder.mockResolvedValue({ deleted_count: count });
    const qc = renderPage(`?folder=${folder}`);
    await screen.findByText("No prompts in this folder");
    seedPromptReads(qc);

    fireEvent.click(screen.getByRole("button", { name: "Delete folder" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(`Delete folder ${folder}?`)).toBeTruthy();
    expect(
      within(dialog).getByText(
        "This permanently deletes every prompt in this folder, with all versions and deployment labels. Applications that fetch them get a not-found error.",
      ),
    ).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete folder" }));

    expect(await screen.findByText(toast)).toBeTruthy();
    expect(api.deletePromptFolder).toHaveBeenCalledWith(folder);
    await waitFor(() => expect(currentParams().get("folder")).toBe(parent));
    expect(promptReadsStale(qc)[0]).toBe(true);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  // Bug: a refused delete closes silently, so the reader believes the folder is gone.
  it("says why a folder was not deleted", async () => {
    list([]);
    usage([]);
    api.deletePromptFolder.mockRejectedValue(new Error("PROMPTS.INVALID_NAME: Invalid prompt name: x"));
    renderPage("?folder=support");
    await screen.findByText("No prompts in this folder");

    fireEvent.click(screen.getByRole("button", { name: "Delete folder" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete folder" }));

    expect(await screen.findByText("Could not delete folder")).toBeTruthy();
    expect(screen.getByText("PROMPTS.INVALID_NAME: Invalid prompt name: x")).toBeTruthy();
  });

  it("closes the confirmation on Cancel", async () => {
    list([]);
    usage([]);
    renderPage("?folder=support");
    await screen.findByText("No prompts in this folder");

    fireEvent.click(screen.getByRole("button", { name: "Delete folder" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(api.deletePromptFolder).not.toHaveBeenCalled();
  });
});
