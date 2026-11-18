// SPDX-License-Identifier: Apache-2.0
/*
 * A new version starts from one version (or the playground's edit). The bugs worth catching are an editor that
 * opens blank or drops the handed-over edit, labels or the commit message dropped, a label move that does not say it
 * moves, a preview that marks the wrong text or misses a config change, a created version the page does not switch
 * to, and a refusal that loses the edit.
 */
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptChatBody, PromptDetail, PromptVersionView } from "../../api/types";
import { api } from "../../test/api";
import { promptDetail, promptVersion } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { renderRoute } from "../../test/render";
import { NewVersionPanel, type VersionDraft } from "./NewVersionPanel";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const BODY: PromptChatBody = [{ role: "system", content: "You are {{tone}}." }, { type: "placeholder", name: "history" }];
const FROM = promptVersion({ body: BODY, config: { temperature: 0.2 } });
const DETAIL = promptDetail();

function renderPanel({ from = FROM, draft, detail = DETAIL }: { from?: PromptVersionView; draft?: VersionDraft; detail?: PromptDetail } = {}) {
  const onCreated = vi.fn();
  const onFrom = vi.fn();
  const { queryClient } = renderRoute(
    <NewVersionPanel
      detail={detail}
      versions={DETAIL.versions}
      from={from}
      onFrom={onFrom}
      draft={draft}
      cancelHref="/prompts/p-1"
      playgroundHref="/prompts/p-1?section=playground&version=2"
      onCreated={onCreated}
    />,
  );
  return { onCreated, onFrom, queryClient };
}

const save = () => fireEvent.click(screen.getByRole("button", { name: "Create version" }));
const content = () => screen.getByRole("textbox", { name: "Content of message 1" }) as HTMLTextAreaElement;
const config = () => screen.getByRole("textbox", { name: "Config JSON" }) as HTMLTextAreaElement;
const changes = () => screen.getByRole("heading", { name: "Changes from version 2" }).closest("div")!.parentElement!;

describe("opening", () => {
  // Bug: the editor opens blank, so a new version silently drops the old body or config.
  it("opens with the start version's body and config", () => {
    renderPanel();

    expect(content().value).toBe("You are {{tone}}.");
    expect((screen.getByRole("textbox", { name: "Placeholder name 2" }) as HTMLInputElement).value).toBe("history");
    expect(config().value).toBe(JSON.stringify({ temperature: 0.2 }, null, 2));
    expect((screen.getByRole("combobox", { name: "Starts from" }) as HTMLSelectElement).value).toBe("2");
  });

  // Bug: the playground's edit is lost on the way to Save as new version.
  it("opens with the handed-over edit instead, and previews it against the start version", () => {
    renderPanel({ draft: { version: 2, body: [{ role: "system", content: "You are {{tone}}. Be brief." }, BODY[1]], config: { temperature: 0.5 } } });

    expect(content().value).toBe("You are {{tone}}. Be brief.");
    expect(within(changes()).getByText("Be brief.").tagName).toBe("INS");
    expect(within(changes()).getByText("0.5").tagName).toBe("INS");
  });

  // Bug: picking another start version does nothing.
  it("starts from the version the select picks", () => {
    const { onFrom } = renderPanel();

    fireEvent.change(screen.getByRole("combobox", { name: "Starts from" }), { target: { value: "1" } });

    expect(onFrom).toHaveBeenCalledWith(1);
  });
});

describe("the preview", () => {
  // Bug: the preview marks the whole message, or says nothing changed after an edit.
  it("marks only the changed words, and says when the config did not change", () => {
    renderPanel();
    expect(within(changes()).getByText("The prompt has no changes.")).toBeTruthy();

    fireEvent.change(content(), { target: { value: "You are {{tone}}. Be brief." } });

    expect(within(changes()).getByText("Be brief.").tagName).toBe("INS");
    expect(within(changes()).getByText("The config has no changes.")).toBeTruthy();
  });
});

// Bug: a long text prompt previewed whole, so the one changed word hides in the page.
it("cuts long unchanged text around each change in a text prompt's preview", () => {
  const words = (prefix: string, n: number) => Array.from({ length: n }, (_, i) => `${prefix}${i}`).join(" ");
  const before = `${words("a", 20)} old ${words("m", 20)} end ${words("z", 20)}`;
  const after = `${words("a", 20)} new ${words("m", 20)} finish ${words("z", 20)}`;
  renderPanel({ from: promptVersion({ type: "text", body: before, config: {} }), detail: promptDetail({ type: "text" }) });
  const box = screen.getByRole("textbox", { name: "Prompt text" });

  fireEvent.change(box, { target: { value: after } });

  const preview = within(changes()).getByText("new").closest("pre")!;
  expect(preview.textContent).toBe("… a16 a17 a18 a19 oldnew m0 m1 m2 m3 … m16 m17 m18 m19 endfinish z0 z1 z2 z3 …");
});

describe("deployment labels", () => {
  // Bug: ticking a label that sits on another version reads as adding it, not moving it.
  it("says where each label is now, and that ticking one moves it", () => {
    renderPanel();
    expect(screen.getByText("On version 2")).toBeTruthy();

    fireEvent.click(screen.getByRole("checkbox", { name: "production" }));

    expect(screen.getByText("Moves from version 2")).toBeTruthy();
    expect((screen.getByRole("checkbox", { name: "latest" }) as HTMLInputElement).disabled).toBe(true);
  });
});

describe("creating", () => {
  // Bug: labels or the commit message dropped, or the page not told which version to open.
  it("sends labels and the commit message, then hands back the new version", async () => {
    const created = promptVersion({ version: 3 });
    api.createPromptVersion.mockResolvedValue(created);
    const { onCreated, queryClient } = renderPanel();
    seedPromptReads(queryClient);
    fireEvent.click(screen.getByRole("checkbox", { name: "staging" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Commit message" }), { target: { value: "Warmer" } });

    save();

    expect(await screen.findByText("Version 3 created")).toBeTruthy();
    expect(api.createPromptVersion).toHaveBeenCalledWith("p-1", {
      body: BODY, config: { temperature: 0.2 }, labels: ["staging"], commit_message: "Warmer",
    });
    expect(onCreated).toHaveBeenCalledWith(created);
    expect(promptReadsStale(queryClient)).toEqual([true, true, true]);
  });

  // Bug: an empty commit message sent as "" instead of null.
  it("sends a null commit message when none is typed", async () => {
    api.createPromptVersion.mockResolvedValue(promptVersion({ version: 3 }));
    const { onCreated } = renderPanel();

    save();

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(api.createPromptVersion).toHaveBeenCalledWith("p-1", {
      body: BODY, config: { temperature: 0.2 }, labels: [], commit_message: null,
    });
  });

  // Bug: a refusal leaves the editor and loses the edit, or says nothing.
  it("says why the version was not created and keeps the edit", async () => {
    api.createPromptVersion.mockRejectedValue(new Error("PROMPTS.INVALID_BODY: Invalid prompt body: body is empty"));
    const { onCreated } = renderPanel();
    fireEvent.change(content(), { target: { value: "Kept" } });

    save();

    expect((await screen.findByRole("alert")).textContent).toContain("PROMPTS.INVALID_BODY: Invalid prompt body: body is empty");
    expect(content().value).toBe("Kept");
    expect(onCreated).not.toHaveBeenCalled();
  });

  // Bug: an invalid body or config sent, then refused by the server.
  it.each([
    ["a blank message", () => fireEvent.change(content(), { target: { value: "" } }), "Enter the message content."],
    ["config that is not an object", () => fireEvent.change(config(), { target: { value: "5" } }), "Config must be a JSON object."],
  ])("refuses %s", (_, spoil, error) => {
    renderPanel();
    spoil();

    save();

    expect(screen.getByText(error)).toBeTruthy();
    expect(api.createPromptVersion).not.toHaveBeenCalled();
  });
});
