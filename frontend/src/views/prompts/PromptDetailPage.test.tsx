// SPDX-License-Identifier: Apache-2.0
/*
 * One prompt: its versions, the selected version's prompt, config, fetch code and traces, its metrics and
 * playground, and the writes on it. The bugs worth catching are reads fired for a prompt that does not exist or for
 * a section nobody opened, a URL version that selects nothing or the wrong one, a version past the first page that
 * cannot be reached, a delete that removes a labeled version or the whole prompt without saying so, a deleted prompt
 * or version the page stays on, and tags that never reach the server.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError, type PromptDetail, type PromptVersionView } from "../../api/types";
import { api, orgApi } from "../../test/api";
import { promptDetail, promptVersion } from "../../test/fixtures";
import { currentLocation, currentParams, pending, renderRoute } from "../../test/render";
import { PromptDetailPage } from "./PromptDetailPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const V1 = promptVersion({
  id: "pv-1", version: 1, labels: [], commit_message: null, created_by: "01M57WWYFWWDDFP8ZKS2MZKW7X",
  created_by_label: "ci@example.com", created_via: "api", config: {},
  body: [{ role: "system", content: "You are kind." }],
});

function serve(detail: PromptDetail = promptDetail(), versions: Record<number, PromptVersionView> = { 1: V1, 2: promptVersion() }) {
  api.getPrompt.mockResolvedValue(detail);
  api.getPromptVersion.mockImplementation((_id: string, v: number) => Promise.resolve(versions[v]));
}

function renderPage(search = "") {
  return renderRoute(<PromptDetailPage />, {
    route: `/orgs/acme/projects/default/prompts/p-1${search}`,
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "prompts/:promptId",
  }).queryClient;
}

const pressed = (name: string) => screen.getByRole("button", { name }).getAttribute("aria-pressed");
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const current = (nav: string) =>
  within(screen.getByRole("navigation", { name: nav })).getAllByRole("link").find((l) => l.getAttribute("aria-current") === "page")
    ?.textContent;
const tabLink = (nav: string, name: string) => within(screen.getByRole("navigation", { name: nav })).getByRole("link", { name });
const menu = (trigger: string, item: string) => {
  fireEvent.click(button(trigger));
  fireEvent.click(screen.getByRole("menuitem", { name: item }));
};
/** A prompt whose first page holds versions 150 down to 51. */
function longPrompt(): PromptDetail {
  const base = promptDetail().versions[1];
  const versions = Array.from({ length: 100 }, (_, i) => ({ ...base, id: `pv-${150 - i}`, version: 150 - i, labels: i === 0 ? ["latest"] : [] }));
  return promptDetail({ latest_version: 150, labels: [], versions, has_older_versions: true });
}

describe("loading the prompt", () => {
  // Bug: a deleted prompt's page fires its version read, and shows a raw 404 with no way back.
  it("says when the prompt does not exist and fires no version read", async () => {
    api.getPrompt.mockRejectedValue(new ApiError(404, { code: "PROMPTS.NOT_FOUND", message: "Prompt p-1 not found" }));
    renderPage();

    expect(await screen.findByText("Prompt not found")).toBeTruthy();
    expect(screen.getByText("It may have been deleted.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Back to prompts" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts");
    expect(api.getPromptVersion).not.toHaveBeenCalled();
  });

  it("shows any other error", async () => {
    api.getPrompt.mockRejectedValue(new ApiError(403, { code: "CAPABILITY.DISABLED", message: "Prompt management is off" }));
    renderPage();

    expect((await screen.findByRole("alert")).textContent).toBe("CAPABILITY.DISABLED: Prompt management is off");
  });

  it("shows loading while the prompt is read", () => {
    api.getPrompt.mockReturnValue(pending());
    renderPage();

    expect(screen.getByText("Loading prompt…")).toBeTruthy();
  });

  // Bug: the selected version's body never loads, or its failure leaves a blank pane.
  it("shows loading while the version is read, then its error", async () => {
    api.getPrompt.mockResolvedValue(promptDetail());
    api.getPromptVersion.mockReturnValue(pending());
    renderPage();
    expect(await screen.findByText("Loading version…")).toBeTruthy();
    expect(button("New version").disabled).toBe(true);
  });

  it("shows the error when the version read fails", async () => {
    api.getPrompt.mockResolvedValue(promptDetail());
    api.getPromptVersion.mockRejectedValue(new ApiError(404, { code: "PROMPTS.VERSION_NOT_FOUND", message: "Prompt p-1 has no version 2" }));
    renderPage();

    expect((await screen.findByRole("alert")).textContent).toBe("PROMPTS.VERSION_NOT_FOUND: Prompt p-1 has no version 2");
  });
});

describe("the header", () => {
  // Bug: a breadcrumb that loses the folder, or a title that shows only the leaf.
  it("titles the page with the full name under a breadcrumb through its folders", async () => {
    serve();
    renderPage();

    expect(await screen.findByRole("heading", { level: 1, name: "support/greeting" })).toBeTruthy();
    const crumbs = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(within(crumbs).getByRole("link", { name: "Prompts" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts");
    expect(within(crumbs).getByRole("link", { name: "support" }).getAttribute("href")).toBe(
      "/orgs/acme/projects/default/prompts?folder=support",
    );
    expect(within(crumbs).getByText("greeting")).toBeTruthy();
  });

  // Bug: the type or the tags missing from the header, so the reader cannot tell a chat prompt from a text one.
  it("shows the type and the tags beside the title", async () => {
    serve(promptDetail({ tags: ["support", "tier-1"] }));
    renderPage();

    await screen.findByRole("heading", { level: 1, name: "support/greeting" });
    expect(screen.getByText("chat")).toBeTruthy();
    expect(screen.getByText("tier-1")).toBeTruthy();
  });

  // Bug: Compare opens on the selected version against itself, or is offered with nothing to compare against.
  it("compares the selected version with the next older one", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    fireEvent.click(button("Compare versions"));

    const dialog = screen.getByRole("dialog");
    expect((within(dialog).getByRole("combobox", { name: "Left version" }) as HTMLSelectElement).value).toBe("1");
    expect((within(dialog).getByRole("combobox", { name: "Right version" }) as HTMLSelectElement).value).toBe("2");
    fireEvent.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("disables Compare versions when the prompt has one version", async () => {
    serve(promptDetail({ versions: promptDetail().versions.slice(1), latest_version: 1 }), { 1: V1 });
    renderPage();

    await screen.findByRole("heading", { level: 2, name: "Version 1" });
    expect(button("Compare versions").disabled).toBe(true);
  });

  // Bug: the playground keeps the first version's template after the person selects another version, or cannot be left.
  it("opens the playground on the selected version and goes back to the versions", async () => {
    serve();
    orgApi.listProviderCatalog.mockResolvedValue({
      platforms: [{ id: "OPENAI", label: "OpenAI", auth: "api_key", default_base_url: "", detail: null, supports_base_url: false, used_by: [] }],
      models: [],
    });
    orgApi.listProviderCredentials.mockResolvedValue({ credentials: [{ provider: "OPENAI", has_api_key: true }] });
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    fireEvent.click(button("Playground"));
    await waitFor(() => expect(currentParams().get("section")).toBe("playground"));
    expect(((await screen.findByRole("textbox", { name: "Content of message 1" })) as HTMLTextAreaElement).value).toBe("You are kind.");

    fireEvent.click(screen.getByRole("link", { name: "Back to versions" }));
    expect(await screen.findByRole("heading", { level: 2, name: "Version 1" })).toBeTruthy();
    expect(currentParams().get("section")).toBeNull();
  });

  // Bug: New version opens blank, so the new version drops the selected one's body.
  it("opens the version editor prefilled from the selected version", async () => {
    serve();
    renderPage("?version=1");
    await screen.findByText("You are kind.");

    fireEvent.click(button("New version"));

    expect(screen.getByText("New version of support/greeting")).toBeTruthy();
    expect((screen.getByRole("textbox", { name: "Content of message 1" }) as HTMLTextAreaElement).value).toBe("You are kind.");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByText("New version of support/greeting")).toBeNull();
  });
});

describe("deleting the prompt", () => {
  // Bug: the page stays on a deleted prompt, or lands at the root instead of the prompt's folder.
  it("confirms, deletes, and lands on the prompt's folder", async () => {
    serve();
    api.deletePrompt.mockResolvedValue({ deleted: true });
    renderPage();
    await screen.findByRole("heading", { level: 1, name: "support/greeting" });

    menu("More actions for this prompt", "Delete prompt");
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Delete greeting?")).toBeTruthy();
    expect(
      within(dialog).getByText(
        "This permanently deletes every version and deployment label of this prompt. Applications that fetch it get a not-found error.",
      ),
    ).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete prompt" }));

    expect(await screen.findByText("Prompt deleted")).toBeTruthy();
    expect(api.deletePrompt).toHaveBeenCalledWith("p-1");
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts?folder=support"));
  });

  it("says why the prompt was not deleted, and Cancel closes", async () => {
    serve();
    api.deletePrompt.mockRejectedValue(new Error("PROMPTS.CONCURRENT_WRITE: retry"));
    renderPage();
    await screen.findByRole("heading", { level: 1, name: "support/greeting" });

    menu("More actions for this prompt", "Delete prompt");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete prompt" }));
    expect(await screen.findByText("Could not delete prompt")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("selecting a version and a section", () => {
  // Bug: ?version= ignored, or a version the prompt lacks selects nothing.
  it.each([
    ["?version=1", "Version 1", 1],
    ["?version=9", "Version 2", 2],
    ["", "Version 2", 2],
  ])("%s selects %s", async (search, name, v) => {
    serve();
    renderPage(search);

    expect(await screen.findByRole("heading", { level: 2, name })).toBeTruthy();
    expect(pressed(name)).toBe("true");
    expect(api.getPromptVersion).toHaveBeenCalledWith("p-1", v);
  });

  // Bug: with the version list paged, a link to a version below the first page jumped to the newest version.
  it("selects a version below the first page from the URL", async () => {
    serve(longPrompt(), { 3: promptVersion({ version: 3, labels: [] }) });
    renderPage("?version=3");

    expect(await screen.findByRole("heading", { level: 2, name: "Version 3" })).toBeTruthy();
    expect(api.getPromptVersion).toHaveBeenCalledWith("p-1", 3);
    expect(api.getPromptVersion).not.toHaveBeenCalledWith("p-1", 150);
  });

  // Bug: a deleted version inside the loaded page is read anyway and shows a 404 instead of the newest version.
  it("falls back to the newest version for a number the loaded page shows is gone", async () => {
    serve(longPrompt(), { 150: promptVersion({ version: 150, labels: ["latest"] }) });
    renderPage("?version=200");

    expect(await screen.findByRole("heading", { level: 2, name: "Version 150" })).toBeTruthy();
    expect(api.getPromptVersion).not.toHaveBeenCalledWith("p-1", 200);
  });

  // Bug: a prompt with more versions than the first page shows no way to the older ones.
  it("shows older versions a page at a time, below the oldest one shown", async () => {
    serve(longPrompt(), { 150: promptVersion({ version: 150, labels: ["latest"] }) });
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 150" });
    expect(api.getPrompt).toHaveBeenCalledWith("p-1", { limit: 100 });
    const older = promptDetail().versions[1];
    api.getPrompt.mockResolvedValueOnce({ ...longPrompt(), versions: [{ ...older, id: "pv-50", version: 50 }], has_older_versions: false });

    fireEvent.click(button("Show older versions"));

    expect(await screen.findByRole("button", { name: "Version 50" })).toBeTruthy();
    expect(api.getPrompt).toHaveBeenLastCalledWith("p-1", { limit: 100, beforeVersion: 51 });
    expect(screen.queryByRole("button", { name: "Show older versions" })).toBeNull();
  });

  // Bug: clicking a version or a tab changes nothing the URL keeps, so a reload or a shared link loses it.
  it("writes the chosen version and tab to the URL", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(current("Version sections")).toBe("Prompt");

    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    await waitFor(() => expect(currentParams().get("version")).toBe("1"));
    await screen.findByRole("heading", { level: 2, name: "Version 1" });
    fireEvent.click(tabLink("Version sections", "Use prompt"));
    await waitFor(() => expect(currentParams().get("tab")).toBe("use"));
    expect(currentParams().get("version")).toBe("1");
    expect(current("Version sections")).toBe("Use prompt");
  });

  // Bug: metrics and traces read on every page open, though most visits never open them.
  it("reads metrics and traces only in their sections", async () => {
    serve();
    api.getPromptUsage.mockResolvedValue({ days: 7, versions: [], unknown_versions: [] });
    api.listTraces.mockResolvedValue({ traces: [], next_cursor: null });
    renderPage();
    await screen.findByText("{{tone}}");
    expect(current("Prompt sections")).toBe("Versions");
    expect(api.getPromptUsage).not.toHaveBeenCalled();
    expect(api.listTraces).not.toHaveBeenCalled();

    fireEvent.click(tabLink("Version sections", "Linked traces"));
    expect(await screen.findByText("No traces for version 2 yet")).toBeTruthy();
    expect(api.getPromptUsage).not.toHaveBeenCalled();
    fireEvent.click(tabLink("Prompt sections", "Metrics"));
    expect(await screen.findByText("No calls in the last 7 days")).toBeTruthy();
    expect(current("Prompt sections")).toBe("Metrics");
  });

  it.each([
    ["?tab=use", "Use prompt"],
    ["?tab=nonsense", "Prompt"],
  ])("%s opens the %s tab", async (search, tab) => {
    serve();
    renderPage(search);

    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(current("Version sections")).toBe(tab);
  });

  // Bug: the config hidden on a tab of its own, or blank for a version without config.
  it("shows the config as JSON below the prompt, or says the version has none", async () => {
    serve();
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Config" })).toBeTruthy();
    expect(screen.getByText(/"temperature": 0.2/)).toBeTruthy();
    expect(screen.getByText("{{tone}}")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    expect(await screen.findByText("No config on this version.")).toBeTruthy();
  });

  // Bug: the variables a caller must pass are only visible inside the text.
  it("lists the variables of the selected version", async () => {
    serve();
    renderPage();

    const vars = await screen.findByRole("list", { name: "Variables" });
    expect(within(vars).getAllByRole("listitem").map((i) => i.textContent)).toEqual(["tone"]);
  });
});

describe("use prompt", () => {
  // Bug: the snippet fetched production on the page of another version, so the reader ran code for a version they
  // did not look at.
  it("fetches the selected version by its label, and by number when chosen", async () => {
    serve(promptDetail({ labels: [{ label: "production", version: 2 }, { label: "staging", version: 2 }] }), {
      1: V1,
      2: promptVersion({ labels: ["production", "staging", "latest"] }),
    });
    renderPage("?tab=use");

    const code = await screen.findByRole("region", { name: "Python snippet" });
    expect(code.textContent).toContain('prompts.get_prompt("support/greeting", label="production")');
    expect(code.textContent).toContain("# links this call to version 2");
    expect(within(screen.getByRole("group", { name: "Fetch by" })).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "Label: production",
      "Label: staging",
      "Version 2",
    ]);
    expect(screen.getByText("This version carries production, so a fetch with no label and no version gets it.")).toBeTruthy();

    fireEvent.click(within(screen.getByRole("group", { name: "Fetch by" })).getByRole("button", { name: "Version 2" }));
    expect(screen.getByRole("region", { name: "Python snippet" }).textContent).toContain('get_prompt("support/greeting", version=2)');
    fireEvent.click(button("TypeScript"));
    expect(screen.getByRole("region", { name: "TypeScript snippet" }).textContent).toContain('prompts.get("support/greeting", { version: 2 })');
  });

  // Bug: a version with no label offers a label fetch that gets another version, and the note hides where production is.
  it("fetches an unlabeled version by number and says where production is", async () => {
    serve();
    renderPage("?version=1&tab=use");

    const code = await screen.findByRole("region", { name: "Python snippet" });
    expect(code.textContent).toContain('get_prompt("support/greeting", version=1)');
    expect(screen.getByText("production is on version 2. A fetch with no label and no version gets the version that carries production.")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Create an API key" }).getAttribute("href")).toBe("/orgs/acme/projects/default/settings/api-keys");
    expect(screen.getByRole("link", { name: "Read the SDK guide" }).getAttribute("href")).toBe("https://docs.tessary.ai/prompts/sdk");
  });

  it("says when no version carries production", async () => {
    serve(promptDetail({ labels: [] }), { 1: V1, 2: promptVersion({ labels: ["latest"] }) });
    renderPage("?tab=use");

    expect(await screen.findByText("No version carries production, so a fetch with no label and no version gets a not-found error.")).toBeTruthy();
  });
});

describe("the version strip", () => {
  // Bug: the wrong author, origin or commit shown, the author shown as a raw principal id, or latest missing from the
  // newest version.
  it("shows the commit, author, origin and labels of the selected version", async () => {
    serve();
    renderPage();

    expect(await screen.findByText(/^Tighten tone · Ana Lima · /)).toBeTruthy();
    expect(screen.queryByText(/01M57WWYFWWDDFP8ZKS2MZKW7W/)).toBeNull();
    expect(screen.getAllByText("latest").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    expect(await screen.findByText(/^No commit message · ci@example.com via API · /)).toBeTruthy();
  });

  // Bug: one click removed the production version with no word that agents fetching production lose it.
  it("deleting a version that carries production asks first and names the label", async () => {
    serve(promptDetail({ labels: [{ label: "production", version: 2 }, { label: "staging", version: 2 }] }), {
      1: V1,
      2: promptVersion({ labels: ["production", "staging", "latest"] }),
    });
    api.deletePromptVersion.mockResolvedValue({ deleted: true });
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    menu("More actions for version 2", "Delete version 2");

    const dialog = screen.getByRole("dialog");
    expect(
      within(dialog).getByText(
        "It carries the deployment labels production and staging. A fetch by those labels gets a not-found error until you move them to another version.",
      ),
    ).toBeTruthy();
    expect(api.deletePromptVersion).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete version 2" }));
    expect(await screen.findByText("Version 2 deleted")).toBeTruthy();
  });

  // Bug: the derived latest label counted as a deployment label, so the confirm warns about a label no fetch uses.
  it("does not name latest as a label of the version", async () => {
    serve(promptDetail({ labels: [] }), { 1: V1, 2: promptVersion({ labels: ["latest"] }) });
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    menu("More actions for version 2", "Delete version 2");

    expect(within(screen.getByRole("dialog")).queryByText(/deployment label/)).toBeNull();
  });

  // Bug: deleting the only version silently deleted the prompt, and the page stayed on a prompt that is gone.
  it("says deleting the only version deletes the prompt, and lands on the folder", async () => {
    serve(promptDetail({ labels: [], versions: promptDetail().versions.slice(1), latest_version: 1 }), { 1: V1 });
    api.deletePromptVersion.mockResolvedValue({ deleted: true });
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    menu("More actions for version 1", "Delete version 1");
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("This is the only version, so deleting it also deletes the prompt.")).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete version 1" }));

    expect(await screen.findByText("Prompt deleted")).toBeTruthy();
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts?folder=support"));
  });

  // Bug: the page stays on the deleted version.
  it("confirms, deletes the version and drops it from the URL", async () => {
    serve();
    api.deletePromptVersion.mockResolvedValue({ deleted: true });
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    menu("More actions for version 1", "Delete version 1");
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Delete version 1?")).toBeTruthy();
    expect(
      within(dialog).getByText("This permanently deletes the version. Spans that name it keep the number and show as an unknown version."),
    ).toBeTruthy();
    fireEvent.click(within(dialog).getByRole("button", { name: "Delete version 1" }));

    expect(await screen.findByText("Version 1 deleted")).toBeTruthy();
    expect(api.deletePromptVersion).toHaveBeenCalledWith("p-1", 1);
    await waitFor(() => expect(currentParams().get("version")).toBeNull());
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  // Bug: deleting the latest version with no version in the URL kept the stale latest_version selected until the
  // detail refetch landed, so the page re-read the deleted version and showed its 404.
  it("moves to the new latest version after deleting the latest one, without a 404", async () => {
    serve(promptDetail({ labels: [] }), { 1: V1, 2: promptVersion({ labels: ["latest"] }) });
    api.deletePromptVersion.mockResolvedValue({ deleted: true });
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    api.getPrompt.mockReturnValue(pending());
    api.getPromptVersion.mockImplementation((_id: string, v: number) =>
      v === 2
        ? Promise.reject(new ApiError(404, { code: "PROMPTS.VERSION_NOT_FOUND", message: "Prompt p-1 has no version 2" }))
        : Promise.resolve(V1),
    );

    menu("More actions for version 2", "Delete version 2");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete version 2" }));

    expect(await screen.findByText("Version 2 deleted")).toBeTruthy();
    expect(await screen.findByRole("heading", { level: 2, name: "Version 1" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("says why a version was not deleted, and Cancel closes", async () => {
    serve();
    api.deletePromptVersion.mockRejectedValue(new Error("PROMPTS.CONCURRENT_WRITE: retry"));
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    menu("More actions for version 1", "Delete version 1");
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete version 1" }));
    expect(await screen.findByText("Could not delete version")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens the label editor for the selected version", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    fireEvent.click(button("Deployment labels"));

    expect(screen.getByRole("dialog", { name: "Deployment labels for version 2" })).toBeTruthy();
  });

  // Bug: Promote to production offered on the version that already carries it, or opening a panel with nothing to save.
  it("promotes a version to production from its own button, offered only where production is not", async () => {
    serve();
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    fireEvent.click(button("Promote to production"));

    const panel = screen.getByRole("dialog", { name: "Deployment labels for version 1" });
    expect((within(panel).getByRole("checkbox", { name: "production" }) as HTMLInputElement).checked).toBe(true);
    expect((within(panel).getByRole("button", { name: "Promote to production" }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(within(panel).getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Version 2" }));
    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(screen.queryByRole("button", { name: "Promote to production" })).toBeNull();
  });
});

describe("linked traces", () => {
  // Bug: an empty table with no word on how a trace comes to link to a version.
  it("says how a trace links to a version", async () => {
    serve();
    api.listTraces.mockResolvedValue({ traces: [], next_cursor: null });
    renderPage("?tab=traces");

    expect(await screen.findByText("No traces for version 2 yet")).toBeTruthy();
    expect(
      screen.getByText("A trace links to a version when one of its spans names the prompt and the version. The SDK adds both with prompt.link(span)."),
    ).toBeTruthy();
  });
});

describe("tags", () => {
  // Bug: edited tags never reach the server, or arrive with blanks and spaces.
  it("saves edited tags", async () => {
    serve(promptDetail({ tags: ["support"] }));
    api.updatePromptTags.mockResolvedValue(promptDetail({ tags: ["a", "b"] }));
    renderPage();
    await screen.findByText("support", { selector: "span" });

    fireEvent.click(button("Edit tags"));
    const box = screen.getByRole("textbox", { name: "Tags" }) as HTMLInputElement;
    expect(box.value).toBe("support");
    fireEvent.change(box, { target: { value: "a, b," } });
    fireEvent.click(button("Save"));

    expect(await screen.findByText("Tags saved")).toBeTruthy();
    expect(api.updatePromptTags).toHaveBeenCalledWith("p-1", ["a", "b"]);
    await waitFor(() => expect(screen.queryByRole("textbox", { name: "Tags" })).toBeNull());
  });

  // Bug: a tag the server refuses sent anyway, or a refused save that closes the editor.
  it("refuses a bad tag and keeps the editor open when a save fails", async () => {
    serve();
    api.updatePromptTags.mockRejectedValue(new Error("PROMPTS.INVALID_TAG: Invalid tag"));
    renderPage();
    await screen.findByRole("heading", { level: 1, name: "support/greeting" });

    fireEvent.click(button("Edit tags"));
    fireEvent.change(screen.getByRole("textbox", { name: "Tags" }), { target: { value: "two words" } });
    fireEvent.click(button("Save"));
    expect(screen.getByText("Use up to 50 tags with no spaces, each at most 64 characters.")).toBeTruthy();
    expect(api.updatePromptTags).not.toHaveBeenCalled();

    fireEvent.change(screen.getByRole("textbox", { name: "Tags" }), { target: { value: "ok" } });
    fireEvent.click(button("Save"));
    expect(await screen.findByText("Could not save tags")).toBeTruthy();
    expect(screen.getByRole("textbox", { name: "Tags" })).toBeTruthy();

    fireEvent.click(button("Cancel"));
    expect(screen.queryByRole("textbox", { name: "Tags" })).toBeNull();
  });
});
