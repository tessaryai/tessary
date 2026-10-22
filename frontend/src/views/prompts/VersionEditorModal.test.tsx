// SPDX-License-Identifier: Apache-2.0
/*
 * A new version starts from the selected one (or the playground's edit). The bugs worth catching are an editor that
 * opens blank, labels or the commit message dropped, a created version the page does not switch to, and a refusal
 * that closes the editor and loses the edit.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptChatBody } from "../../api/types";
import { api } from "../../test/api";
import { promptVersion } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { currentParams, renderRoute } from "../../test/render";
import { VersionEditorModal } from "./VersionEditorModal";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

const BODY: PromptChatBody = [{ role: "system", content: "You are {{tone}}." }, { type: "placeholder", name: "history" }];

function renderEditor(onCreated?: (v: unknown) => void) {
  const onClose = vi.fn();
  const { queryClient } = renderRoute(
    <VersionEditorModal
      open
      promptId="p-1"
      name="support/greeting"
      type="chat"
      initialBody={BODY}
      initialConfig={{ temperature: 0.2 }}
      onClose={onClose}
      onCreated={onCreated}
    />,
    { route: "/prompts/p-1?version=2&tab=config" },
  );
  return { onClose, queryClient };
}

const save = () => fireEvent.click(screen.getByRole("button", { name: "Create version" }));

// Bug: the editor opens blank, so a new version silently drops the old body or config.
it("opens with the given body and config", () => {
  renderEditor();

  expect(screen.getByText("New version of support/greeting")).toBeTruthy();
  expect((screen.getByRole("textbox", { name: "Content of message 1" }) as HTMLTextAreaElement).value).toBe("You are {{tone}}.");
  expect((screen.getByRole("textbox", { name: "Placeholder name 2" }) as HTMLInputElement).value).toBe("history");
  expect((screen.getByRole("textbox", { name: "Config JSON" }) as HTMLTextAreaElement).value).toBe(
    JSON.stringify({ temperature: 0.2 }, null, 2),
  );
});

describe("creating", () => {
  // Bug: labels or the commit message dropped, or the page left on the old version and tab.
  it("sends labels and the commit message, then opens the new version's body", async () => {
    const created = promptVersion({ version: 3 });
    api.createPromptVersion.mockResolvedValue(created);
    const onCreated = vi.fn();
    const { onClose, queryClient } = renderEditor(onCreated);
    seedPromptReads(queryClient);
    fireEvent.click(screen.getByRole("switch", { name: "staging" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Commit message" }), { target: { value: "Warmer" } });

    save();

    expect(await screen.findByText("Version 3 created")).toBeTruthy();
    expect(api.createPromptVersion).toHaveBeenCalledWith("p-1", {
      body: BODY, config: { temperature: 0.2 }, labels: ["staging"], commit_message: "Warmer",
    });
    // The router writes the URL in a transition, after the toast.
    await waitFor(() => expect(currentParams().get("version")).toBe("3"));
    expect(currentParams().get("tab")).toBe("body");
    expect(onCreated).toHaveBeenCalledWith(created);
    expect(onClose).toHaveBeenCalled();
    expect(promptReadsStale(queryClient)).toEqual([true, true, true]);
  });

  // Bug: a caller with no onCreated (the detail page) crashes the success path.
  it("creates without an onCreated callback and sends a null commit message when none is typed", async () => {
    api.createPromptVersion.mockResolvedValue(promptVersion({ version: 3 }));
    const { onClose } = renderEditor();

    save();

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(api.createPromptVersion).toHaveBeenCalledWith("p-1", {
      body: BODY, config: { temperature: 0.2 }, labels: [], commit_message: null,
    });
  });

  // Bug: a refusal closes the editor and loses the edit, or says nothing.
  it("says why the version was not created and keeps the editor open", async () => {
    api.createPromptVersion.mockRejectedValue(new Error("PROMPTS.VERSION_LIMIT: Prompt p-1 already has 1000 versions"));
    const { onClose } = renderEditor();

    save();

    expect(await screen.findByText("Could not create version")).toBeTruthy();
    expect(screen.getByText("PROMPTS.VERSION_LIMIT: Prompt p-1 already has 1000 versions")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  // Bug: an invalid body or config sent, then refused by the server.
  it.each([
    ["a blank message", () => fireEvent.change(screen.getByRole("textbox", { name: "Content of message 1" }), { target: { value: "" } }),
      "Enter the message content."],
    ["config that is not an object", () => fireEvent.change(screen.getByRole("textbox", { name: "Config JSON" }), { target: { value: "5" } }),
      "Config must be a JSON object."],
  ])("refuses %s", (_, spoil, error) => {
    renderEditor();
    spoil();

    save();

    expect(screen.getByText(error)).toBeTruthy();
    expect(api.createPromptVersion).not.toHaveBeenCalled();
  });
});
