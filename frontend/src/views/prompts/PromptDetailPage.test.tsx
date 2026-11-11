// SPDX-License-Identifier: Apache-2.0
/*
 * One prompt: its versions, the selected version's body, config, usage, traces and playground, and the writes on
 * it. The bugs worth catching are reads fired for a prompt that does not exist or for a tab nobody opened, a URL
 * version that selects nothing, a delete offered where the server refuses it, a deleted prompt or version the page
 * stays on, and tags that never reach the server.
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

    fireEvent.click(button("Delete prompt"));
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

    fireEvent.click(button("Delete prompt"));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete prompt" }));
    expect(await screen.findByText("Could not delete prompt")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("selecting a version and a tab", () => {
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

  // Bug: clicking a version or a tab changes nothing the URL keeps, so a reload or a shared link loses it.
  it("writes the chosen version and tab to the URL", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    await waitFor(() => expect(currentParams().get("version")).toBe("1"));
    await screen.findByRole("heading", { level: 2, name: "Version 1" });
    fireEvent.click(screen.getByRole("button", { name: "Config" }));
    await waitFor(() => expect(currentParams().get("tab")).toBe("config"));
  });

  // Bug: usage and traces read on every page open, though most visits never open those tabs.
  it("reads usage and traces only on their tabs", async () => {
    serve();
    api.getPromptUsage.mockResolvedValue({ days: 7, versions: [], unknown_versions: [] });
    api.listTraces.mockResolvedValue({ traces: [], next_cursor: null });
    renderPage();
    await screen.findByText("{{tone}}");
    expect(pressed("Body")).toBe("true");
    expect(api.getPromptUsage).not.toHaveBeenCalled();
    expect(api.listTraces).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Usage" }));
    expect(await screen.findByText("No calls in the last 7 days")).toBeTruthy();
    expect(api.listTraces).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Traces" }));
    expect(await screen.findByText("No traces for version 2 yet")).toBeTruthy();
  });

  it.each([
    ["?tab=usage", "Usage"],
    ["?tab=nonsense", "Body"],
  ])("%s opens the %s tab", async (search, tab) => {
    serve();
    api.getPromptUsage.mockReturnValue(pending());
    renderPage(search);

    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(pressed(tab)).toBe("true");
  });

  // Bug: the config tab blank for a version without config, or the download named after another version.
  it("shows the config as JSON, or says the version has none", async () => {
    serve();
    renderPage("?tab=config");
    expect(await screen.findByText("greeting.v2.config.json")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    expect(await screen.findByText("No config on this version.")).toBeTruthy();
  });

  // Bug: the playground keeps the first version's template after the person selects another version.
  it("mounts the playground on its tab, seeded from the selected version", async () => {
    serve();
    orgApi.listProviderCatalog.mockResolvedValue({
      platforms: [{ id: "OPENAI", label: "OpenAI", auth: "api_key", default_base_url: "", detail: null, supports_base_url: false, used_by: [] }],
      models: [],
    });
    orgApi.listProviderCredentials.mockResolvedValue({ credentials: [{ provider: "OPENAI", has_api_key: true }] });
    renderPage("?tab=playground");
    const content = async () => ((await screen.findByRole("textbox", { name: "Content of message 1" })) as HTMLTextAreaElement).value;

    expect(await content()).toBe("You are {{tone}}.");
    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    await screen.findByRole("heading", { level: 2, name: "Version 1" });
    expect(await content()).toBe("You are kind.");
    // Version 2 is cached now, so the page swaps the props without a loading row in between.
    fireEvent.click(screen.getByRole("button", { name: "Version 2" }));
    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(await content()).toBe("You are {{tone}}.");
  });

  // Bug: Compare opens on the selected version against itself.
  it("compares the selected version with the next older one", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    fireEvent.click(button("Compare"));

    const dialog = screen.getByRole("dialog");
    expect((within(dialog).getByRole("combobox", { name: "Left version" }) as HTMLSelectElement).value).toBe("1");
    expect((within(dialog).getByRole("combobox", { name: "Right version" }) as HTMLSelectElement).value).toBe("2");
    fireEvent.click(within(dialog).getByRole("button", { name: "Close" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("the version strip", () => {
  // Bug: the wrong author, origin or commit shown, the author shown as a raw principal id, or latest missing from the
  // newest version.
  it("shows the commit, author, origin and labels of the selected version", async () => {
    serve();
    renderPage();

    expect(await screen.findByText(/^Tighten tone · created by Ana Lima via UI · /)).toBeTruthy();
    expect(screen.queryByText(/01M57WWYFWWDDFP8ZKS2MZKW7W/)).toBeNull();
    expect(screen.getAllByText("latest").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "Version 1" }));
    expect(await screen.findByText(/^No commit message · created by ci@example.com via API · /)).toBeTruthy();
  });

  // Bug: a delete offered on a labeled version (the server refuses it) with no visible reason.
  it("disables deleting a version with a deployment label and says why", async () => {
    serve();
    renderPage();

    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(button("Delete version 2").disabled).toBe(true);
    expect(screen.getByText("Move its deployment labels to another version first.")).toBeTruthy();
  });

  // Bug: the derived latest label counted as a deployment label, so the newest version can never be deleted.
  it("allows deleting a version that carries only latest", async () => {
    serve(promptDetail(), { 1: V1, 2: promptVersion({ labels: ["latest"] }) });
    renderPage();

    await screen.findByRole("heading", { level: 2, name: "Version 2" });
    expect(button("Delete version 2").disabled).toBe(false);
    expect(screen.queryByText("Move its deployment labels to another version first.")).toBeNull();
  });

  // Bug: the only version offered for delete, which the server refuses with LAST_VERSION.
  it("disables deleting the only version and points to deleting the prompt", async () => {
    serve(promptDetail({ versions: promptDetail().versions.slice(1), latest_version: 1 }), { 1: V1 });
    renderPage();

    await screen.findByRole("heading", { level: 2, name: "Version 1" });
    expect(button("Delete version 1").disabled).toBe(true);
    expect(screen.getByText("Delete the prompt instead.")).toBeTruthy();
  });

  // Bug: the page stays on the deleted version.
  it("confirms, deletes the version and drops it from the URL", async () => {
    serve();
    api.deletePromptVersion.mockResolvedValue({ deleted: true });
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    fireEvent.click(button("Delete version 1"));
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

    fireEvent.click(button("Delete version 2"));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete version 2" }));

    expect(await screen.findByText("Version 2 deleted")).toBeTruthy();
    expect(await screen.findByRole("heading", { level: 2, name: "Version 1" })).toBeTruthy();
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("says why a version was not deleted, and Cancel closes", async () => {
    serve();
    api.deletePromptVersion.mockRejectedValue(new Error("PROMPTS.VERSION_LABELED: Version 1 carries label staging"));
    renderPage("?version=1");
    await screen.findByRole("heading", { level: 2, name: "Version 1" });

    fireEvent.click(button("Delete version 1"));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Delete version 1" }));
    expect(await screen.findByText("Could not delete version")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("opens the label editor for the selected version", async () => {
    serve();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "Version 2" });

    fireEvent.click(button("Labels"));

    expect(screen.getByRole("dialog", { name: "Deployment labels for version 2" })).toBeTruthy();
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
