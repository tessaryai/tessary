// SPDX-License-Identifier: Apache-2.0
/*
 * The Prompts list. The bugs worth catching are calls joined to the wrong prompt, folders that do not open or
 * cannot be left, a folder or prompt past the first page that cannot be reached, filters that change the URL but
 * not the read, pickers that offer only what is on screen, a long list that reads as complete, an empty state that
 * offers the wrong way out, and a folder delete that leaves the reader inside the folder it removed.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { FolderSummary, PromptSummary } from "../../api/types";
import { api } from "../../test/api";
import { promptSummary } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { currentLocation, currentParams, pending, renderRoute } from "../../test/render";
import { PromptsPage } from "./PromptsPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const GREETING = promptSummary({
  id: "p-1", name: "support/greeting", type: "chat", latest_version: 4, tags: ["support", "tier-1"],
  labels: [{ label: "production", version: 4 }, { label: "staging", version: 3 }],
});
const REFUND = promptSummary({ id: "p-2", name: "support/refund", type: "text", latest_version: 2, labels: [] });
const INTRO = promptSummary({ id: "p-4", name: "intro" });

function folderSummary(path: string, count: number): FolderSummary {
  return { path, name: path.slice(path.lastIndexOf("/") + 1), prompt_count: count, updated_at: "2026-10-01T00:00:00Z" };
}
const TEAM = folderSummary("team", 3);

function level(items: PromptSummary[] = [INTRO], folders: FolderSummary[] = [TEAM], total = items.length) {
  api.listPromptLevel.mockResolvedValue({ folder: null, folders, items, page: 1, limit: 50, total });
}
function searchResult(items: PromptSummary[], total = items.length) {
  api.listPrompts.mockResolvedValue({ items, page: 1, limit: 50, total });
}
function usage(items = [{ name: "support/greeting", calls: 1204 }]) {
  api.getPromptUsageSummary.mockResolvedValue({ days: 7, items });
}

beforeEach(() => {
  usage();
  api.getPromptFacets.mockResolvedValue({ labels: ["canary", "production"], tags: ["support", "tier-1"] });
});

function renderPage(search = "") {
  return renderRoute(<PromptsPage />, {
    route: `/orgs/acme/projects/default/prompts${search}`,
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "prompts",
  }).queryClient;
}

const lastLevelCall = () => api.listPromptLevel.mock.calls.at(-1)![0];
const lastSearchCall = () => api.listPrompts.mock.calls.at(-1)![0];
const rowOf = (text: string) => screen.getByText(text).closest("tr")!;
const cellsOf = (text: string) => within(rowOf(text)).getAllByRole("cell").map((c) => c.textContent);
const rowTexts = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent);

describe("rows", () => {
  // Bug: calls joined by leaf name or row index, so one prompt shows another's calls, or a prompt without calls shows a blank.
  it("shows each prompt's type, latest version, labels with versions, calls joined by full name, and tags", async () => {
    level([GREETING, REFUND], []);
    renderPage("?folder=support");

    await screen.findByRole("link", { name: "greeting" });
    expect(cellsOf("greeting")).toEqual(["greeting", "chat", "v4", "production · v4staging · v3", "1,204", "supporttier-1", expect.any(String)]);
    expect(cellsOf("refund").slice(0, 6)).toEqual(["refund", "text", "v2", "No labels", "0", ""]);
    expect(screen.getByRole("link", { name: "greeting" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts/p-1");
    expect(lastLevelCall()).toEqual({ folder: "support", page: 1, limit: 50 });
  });

  // Bug: a folder row that is not a link, so it cannot be opened from the keyboard or in a new tab, or a count that is
  // the prompts on screen instead of the server's.
  it("lists child folders first, as links with their prompt count, then the prompts", async () => {
    level([INTRO], [TEAM, folderSummary("zeta", 1)]);
    renderPage();

    await screen.findByRole("link", { name: "team" });
    expect(rowTexts()).toEqual(["team· 3 prompts", "zeta· 1 prompt", "intro"]);
    expect(screen.getByRole("link", { name: "team" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts?folder=team");
    fireEvent.click(screen.getByRole("link", { name: "team" }));
    await waitFor(() => expect(currentParams().get("folder")).toBe("team"));
    await waitFor(() => expect(lastLevelCall()).toEqual({ folder: "team", page: 1, limit: 50 }));
  });

  // Bug: a search shows leaf names, so two prompts called greeting in different folders look alike, shows folders, or
  // drops the folder, so the breadcrumb says support while the rows come from every folder.
  it("lists full names, flat, while searching inside the folder", async () => {
    searchResult([GREETING, promptSummary({ id: "p-3", name: "team/a/greeting" })]);
    renderPage("?q=greet&folder=support");

    expect(await screen.findByRole("link", { name: "support/greeting" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "team/a/greeting" })).toBeTruthy();
    expect(lastSearchCall()).toEqual({ q: "greet", folder: "support", page: 1, limit: 50 });
    expect(api.listPromptLevel).not.toHaveBeenCalled();
    expect(screen.getByText("2 prompts match this search.")).toBeTruthy();
  });
});

describe("paging", () => {
  // Bug: the browser grouped the first 200 prompts into folders, so a folder whose prompts sat past row 200 never showed.
  it("a folder that is past the first page still shows", async () => {
    level(Array.from({ length: 50 }, (_, i) => promptSummary({ id: `p-${i}`, name: `a-${String(i).padStart(2, "0")}` })), [folderSummary("zz-last", 2)], 120);
    renderPage();

    expect(await screen.findByRole("link", { name: "zz-last" })).toBeTruthy();
    expect(screen.getByText("120 prompts and 1 folder. A / in a prompt name puts the prompt in a folder.")).toBeTruthy();
  });

  // Bug: prompts past the first page cannot be reached without a search.
  it("scrolling loads the next page of prompts", async () => {
    const original = window.IntersectionObserver;
    class Intersecting {
      constructor(private cb: IntersectionObserverCallback) {}
      observe() {
        this.cb([{ isIntersecting: true } as IntersectionObserverEntry], this as unknown as IntersectionObserver);
      }
      disconnect() {}
    }
    window.IntersectionObserver = Intersecting as unknown as typeof IntersectionObserver;
    try {
      api.listPromptLevel
        .mockResolvedValueOnce({ folder: "support", folders: [], items: [GREETING], page: 1, limit: 50, total: 51 })
        .mockResolvedValueOnce({ folder: "support", folders: [], items: [REFUND], page: 2, limit: 50, total: 51 });
      renderPage("?folder=support");

      expect(await screen.findByRole("link", { name: "refund" })).toBeTruthy();
      expect(lastLevelCall()).toEqual({ folder: "support", page: 2, limit: 50 });
      expect(rowTexts()).toEqual(["greeting", "refund"]);
    } finally {
      window.IntersectionObserver = original;
    }
  });

  // Bug: the only way to the next page is a scroll, which a keyboard or screen reader cannot make.
  it("loads the next page from the button, and offers no button on the last page", async () => {
    api.listPromptLevel
      .mockResolvedValueOnce({ folder: null, folders: [], items: [GREETING], page: 1, limit: 50, total: 51 })
      .mockResolvedValueOnce({ folder: null, folders: [], items: [REFUND], page: 2, limit: 50, total: 51 });
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Load more prompts" }));

    expect(await screen.findByRole("link", { name: "refund" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Load more prompts" })).toBeNull();
  });
});

describe("the breadcrumb", () => {
  // Bug: no way back to the root, or a crumb that links to its own segment instead of the path up to it.
  it("links each folder above the current one", async () => {
    level([promptSummary({ id: "p-3", name: "team/a/x" })], []);
    renderPage("?folder=team/a");

    const crumbs = await screen.findByRole("navigation", { name: "Breadcrumb" });
    expect(within(crumbs).getByRole("link", { name: "team" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts?folder=team");
    fireEvent.click(within(crumbs).getByRole("link", { name: "Prompts" }));
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts"));
  });

  it("has no breadcrumb at the root", async () => {
    level();
    renderPage();

    await screen.findByRole("link", { name: "team" });
    expect(screen.queryByRole("navigation", { name: "Breadcrumb" })).toBeNull();
  });
});

describe("filters", () => {
  // Bug: a search per keystroke, untrimmed, or one that cannot be cleared.
  it("searches on Enter, trimmed, and clears on a blank Enter", async () => {
    level();
    searchResult([GREETING]);
    renderPage();
    const box = await screen.findByRole("textbox", { name: "Search prompts by name" });

    fireEvent.change(box, { target: { value: "  greet  " } });
    fireEvent.keyDown(box, { key: "a" });
    expect(currentParams().get("q")).toBeNull();
    fireEvent.keyDown(box, { key: "Enter" });
    await waitFor(() => expect(lastSearchCall()).toMatchObject({ q: "greet" }));
    expect(currentParams().get("q")).toBe("greet");

    const cleared = screen.getByRole("textbox", { name: "Search prompts by name" });
    fireEvent.change(cleared, { target: { value: " " } });
    fireEvent.keyDown(cleared, { key: "Enter" });
    await waitFor(() => expect(currentParams().get("q")).toBeNull());
  });

  // Bug: the pickers offered only the labels of the rows on screen, so at a root that holds only folders nothing
  // could be picked; or a picker writes the URL but the read ignores it.
  it("offers every label and tag of the project and narrows the read by each", async () => {
    level([], [TEAM]);
    renderPage();
    await screen.findByRole("link", { name: "team" });

    const labels = screen.getByRole("combobox", { name: "Deployment label" }) as HTMLSelectElement;
    await waitFor(() => expect(Array.from(labels.options).map((o) => o.textContent)).toEqual(["Any label", "production", "staging", "canary"]));
    fireEvent.change(labels, { target: { value: "canary" } });
    await waitFor(() => expect(lastLevelCall()).toEqual({ label: "canary", page: 1, limit: 50 }));

    const tags = screen.getByRole("combobox", { name: "Tag" }) as HTMLSelectElement;
    expect(Array.from(tags.options).map((o) => o.textContent)).toEqual(["Any tag", "support", "tier-1"]);
    fireEvent.change(tags, { target: { value: "tier-1" } });
    await waitFor(() => expect(lastLevelCall()).toEqual({ label: "canary", tag: "tier-1", page: 1, limit: 50 }));
    fireEvent.change(screen.getByRole("combobox", { name: "Deployment label" }), { target: { value: "" } });
    await waitFor(() => expect(currentParams().get("label")).toBeNull());
  });

  // Bug: a label or tag from the URL that no prompt carries leaves the picker showing "Any".
  it("keeps a label and a tag from the URL in the pickers", async () => {
    level([], []);
    renderPage("?label=beta&tag=old");

    await screen.findByText("No prompts match these filters");
    expect((screen.getByRole("combobox", { name: "Deployment label" }) as HTMLSelectElement).value).toBe("beta");
    expect((screen.getByRole("combobox", { name: "Tag" }) as HTMLSelectElement).value).toBe("old");
  });
});

describe("states", () => {
  // Bug: a long list reads as every prompt, or the line counts the rows on screen instead of the server's total.
  it("counts the folder's prompts and folders from the server", async () => {
    level([GREETING], [folderSummary("support/escalations", 3)], 4);
    renderPage("?folder=support");

    expect(await screen.findByText("4 prompts and 1 folder in support. A / in a prompt name puts the prompt in a folder.")).toBeTruthy();
  });

  // Bug: a new project told to clear filters it never set, or sent to a form that is not the create screen.
  it("offers a new prompt and the API reference when the project has none", async () => {
    level([], []);
    usage([]);
    renderPage();

    expect(await screen.findByText("No prompts yet")).toBeTruthy();
    expect(screen.getByText("Create a prompt on this page. You can also create prompts with the prompts API or the SDKs.")).toBeTruthy();
    // Bug: search and filter controls offered over a project with nothing to search.
    expect(screen.queryByRole("textbox", { name: "Search prompts by name" })).toBeNull();
    expect(screen.getByRole("link", { name: "Read the prompts API reference" }).getAttribute("href")).toBe(
      "https://docs.tessary.ai/reference/prompts-api",
    );
    fireEvent.click(screen.getAllByRole("button", { name: "New prompt" })[1]);
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts/new"));
  });

  // Bug: the empty result of the old filters stood in while the new read ran, so clearing a filter flashed
  // "No prompts yet" and hid the filters on a project that has prompts.
  it("shows the skeleton, not an empty state, while a filter change is read", async () => {
    level([], []);
    renderPage("?label=beta");
    await screen.findByText("No prompts match these filters");
    api.listPromptLevel.mockReturnValue(pending());

    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    expect(await screen.findByRole("status", { name: "Loading table" })).toBeTruthy();
    expect(screen.queryByText("No prompts yet")).toBeNull();
    expect(screen.getByRole("textbox", { name: "Search prompts by name" })).toBeTruthy();
  });

  // Bug: Clear filters also leaves the folder, or leaves the filter that emptied the list.
  it("clears the filters but keeps the folder when nothing matches", async () => {
    level([], []);
    searchResult([]);
    usage([]);
    renderPage("?folder=support&label=beta&q=x&tag=old");

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
    level([], []);
    usage([]);
    renderPage(`?folder=${encodeURIComponent(folder)}`);

    expect(await screen.findByText("No prompts in this folder")).toBeTruthy();
    expect(screen.getByText(`A prompt is in this folder when its name starts with ${folder}/.`)).toBeTruthy();
    expect(screen.queryByText("No prompts match these filters")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: button }));

    await waitFor(() => expect(currentLocation()).toBe(to));
  });

  // Bug: a slow or failed usage read shows 0 calls, which reads as "never called".
  it("shows calls as counting while usage loads", async () => {
    level([GREETING], []);
    api.getPromptUsageSummary.mockReturnValue(pending());
    renderPage("?folder=support");

    expect((await screen.findByText("…")).getAttribute("title")).toBe("Counting calls");
  });

  it("shows unknown calls and the error when usage fails", async () => {
    level([GREETING], []);
    api.getPromptUsageSummary.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: days"));
    renderPage("?folder=support");

    expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: days");
    expect(cellsOf("greeting")[4]).toBe("—");
  });

  it("shows a skeleton while the list loads", () => {
    api.listPromptLevel.mockReturnValue(pending());
    renderPage();

    expect(screen.getByRole("status", { name: "Loading table" })).toBeTruthy();
  });

  it("shows the error when the list fails", async () => {
    api.listPromptLevel.mockRejectedValue(new Error("COMMON.INVALID_PARAMETER: page"));
    renderPage();

    expect((await screen.findByRole("alert")).textContent).toContain("COMMON.INVALID_PARAMETER: page");
  });
});

describe("new prompt", () => {
  // Bug: the button opens a form that does not know the folder the reader is in, so the name must be typed whole.
  it("opens the create screen in the current folder", async () => {
    level([GREETING], []);
    renderPage("?folder=support");
    await screen.findByRole("link", { name: "greeting" });

    fireEvent.click(screen.getByRole("button", { name: "New prompt" }));

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts/new?folder=support"));
  });
});

describe("deleting a folder", () => {
  const openDelete = () => {
    fireEvent.click(screen.getByRole("button", { name: "More actions for this folder" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Delete folder" }));
  };

  // Bug: Delete folder offered at the root, where it would mean every prompt.
  it("is offered only inside a folder", async () => {
    level();
    renderPage();

    await screen.findByRole("link", { name: "team" });
    expect(screen.queryByRole("button", { name: "More actions for this folder" })).toBeNull();
  });

  // Bug: the reader left inside the folder they removed, a miscounted toast, or the list still cached.
  it.each([
    ["team/a", 3, "Deleted 3 prompts", "team"],
    ["support", 1, "Deleted 1 prompt", null],
  ])("deletes %s after confirming and moves up a level", async (folder, count, toast, parent) => {
    level([], []);
    usage([]);
    api.deletePromptFolder.mockResolvedValue({ deleted_count: count });
    const qc = renderPage(`?folder=${folder}`);
    await screen.findByText("No prompts in this folder");
    seedPromptReads(qc);

    openDelete();
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
    level([], []);
    usage([]);
    api.deletePromptFolder.mockRejectedValue(new Error("PROMPTS.INVALID_NAME: Invalid prompt name: x"));
    renderPage("?folder=support");
    await screen.findByText("No prompts in this folder");

    openDelete();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete folder" }));

    expect(await screen.findByText("Could not delete folder")).toBeTruthy();
    expect(screen.getByText("PROMPTS.INVALID_NAME: Invalid prompt name: x")).toBeTruthy();
  });

  it("closes the confirmation on Cancel", async () => {
    level([], []);
    usage([]);
    renderPage("?folder=support");
    await screen.findByText("No prompts in this folder");

    openDelete();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(api.deletePromptFolder).not.toHaveBeenCalled();
  });
});
