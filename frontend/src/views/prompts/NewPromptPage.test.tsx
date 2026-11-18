// SPDX-License-Identifier: Apache-2.0
/*
 * The New prompt screen. The client copies the server's rules so the reader learns what is wrong before anything is
 * sent. The bugs worth catching are a request the server refuses sent anyway, a name the server accepts refused here,
 * a field missing from or added to the request, a taken name that only says "conflict", a created prompt that leaves
 * the reader on the form, and a screen opened from a folder that forgets it.
 */
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/types";
import { api } from "../../test/api";
import { promptDetail, promptSummary } from "../../test/fixtures";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import { currentLocation, renderRoute } from "../../test/render";
import { NewPromptPage } from "./NewPromptPage";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

function renderModal(search = "") {
  const { queryClient } = renderRoute(<NewPromptPage />, {
    route: `/orgs/acme/projects/default/prompts/new${search}`,
    parent: "/orgs/:orgSlug/projects/:projectSlug",
    path: "prompts/new",
  });
  return { queryClient };
}

const type = (name: string, value: string) => fireEvent.change(screen.getByRole("textbox", { name }), { target: { value } });
const click = (name: string) => fireEvent.click(screen.getByRole("button", { name }));
const create = () => click("Create prompt");

/** A valid text prompt, then `spoil` breaks one field. */
function fillValid(spoil: () => void = () => {}) {
  type("Name", "support/greeting");
  type("Prompt text", "Hi {{name}}");
  spoil();
}

describe("validation", () => {
  // Bug: a request the server refuses with a 400 is sent, after the reader filled every other field.
  it.each([
    ["an empty name", () => type("Name", ""), "Enter a name."],
    ["a name over 255 characters", () => type("Name", "a".repeat(256)), "Use at most 255 characters."],
    ["a blank name", () => type("Name", "   "), "Enter a name."],
    ["a name with a pipe", () => type("Name", "a|b"), "A name cannot contain |."],
    ["a name with an HTML tag", () => type("Name", "a<b>c"), "A name cannot contain an HTML tag."],
    ["a leading /", () => type("Name", "/a"), "Folders are separated by one /, with a name on both sides."],
    ["a trailing /", () => type("Name", "a/"), "Folders are separated by one /, with a name on both sides."],
    ["a double /", () => type("Name", "a//b"), "Folders are separated by one /, with a name on both sides."],
    ["blank text", () => type("Prompt text", "  "), "Enter the prompt text."],
    ["config that is not an object", () => type("Config JSON", "[]"), "Config must be a JSON object."],
    ["a tag with a space", () => type("Tags", "a b"), "Use up to 50 tags with no spaces, each at most 64 characters."],
    ["more than 50 tags", () => type("Tags", Array.from({ length: 51 }, (_, i) => `t${i}`).join(",")),
      "Use up to 50 tags with no spaces, each at most 64 characters."],
    ["a tag over 64 characters", () => type("Tags", "a".repeat(65)), "Use up to 50 tags with no spaces, each at most 64 characters."],
  ])("refuses %s", (_, spoil, error) => {
    renderModal();
    fillValid(spoil);

    create();

    expect(screen.getByText(error)).toBeTruthy();
    expect(api.createPrompt).not.toHaveBeenCalled();
  });

  // Bug: a chat body with no message, a blank message, or a dotted placeholder name sent to the server.
  it.each([
    ["no message", () => {
      click("Remove item 1");
      click("Add placeholder");
      type("Placeholder name 1", "history");
    }, "Add at least one message."],
    ["a blank message", () => {}, "Enter the message content."],
    ["a dotted placeholder name", () => {
      type("Content of message 1", "Hi");
      click("Add placeholder");
      type("Placeholder name 2", "a.b");
    }, "A placeholder name starts with a letter or _ and has only letters, digits and _."],
  ])("refuses a chat body with %s", (_, edit, error) => {
    renderModal();
    type("Name", "support/greeting");
    click("Chat");
    edit();

    create();

    expect(screen.getByText(error)).toBeTruthy();
    expect(api.createPrompt).not.toHaveBeenCalled();
  });
});

describe("creating", () => {
  // Bug: tags, labels or the commit message dropped from the request, or a duplicate tag sent.
  it("sends a valid text prompt exactly", async () => {
    api.createPrompt.mockResolvedValue(promptDetail());
    renderModal();
    fillValid();
    type("Tags", "a, b, a");
    fireEvent.click(screen.getByRole("checkbox", { name: "production" }));
    type("Commit message", "First");

    create();

    await waitFor(() =>
      expect(api.createPrompt).toHaveBeenCalledWith({
        name: "support/greeting", type: "text", body: "Hi {{name}}", config: {}, labels: ["production"],
        tags: ["a", "b"], commit_message: "First",
      }),
    );
  });

  // Bug: the chat body sent as the text box's value, or a placeholder sent as a message.
  it("sends a valid chat prompt exactly", async () => {
    api.createPrompt.mockResolvedValue(promptDetail());
    renderModal();
    type("Name", "support/greeting");
    click("Chat");
    type("Content of message 1", "Be {{tone}}.");
    click("Add placeholder");
    type("Placeholder name 2", "history");
    type("Config JSON", '{"temperature": 0.2}');

    create();

    await waitFor(() =>
      expect(api.createPrompt).toHaveBeenCalledWith({
        name: "support/greeting", type: "chat",
        body: [{ role: "system", content: "Be {{tone}}." }, { type: "placeholder", name: "history" }],
        config: { temperature: 0.2 }, labels: [], tags: [], commit_message: null,
      }),
    );
  });

  // Bug: a created prompt that leaves the reader on the list, with the list still cached without it.
  it("opens the new prompt, says so, and refreshes the list", async () => {
    api.createPrompt.mockResolvedValue(promptDetail({ id: "p-7" }));
    const { queryClient } = renderModal();
    seedPromptReads(queryClient);
    fillValid();

    create();

    expect(await screen.findByText("Prompt created")).toBeTruthy();
    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts/p-7"));
    expect(promptReadsStale(queryClient)).toEqual([true, true, true]);
  });

  // Bug: a taken name answered with a bare conflict code, with no way to the prompt that holds the name.
  it("links to the prompt that already has the name", async () => {
    api.createPrompt.mockRejectedValue(new ApiError(409, { code: "PROMPTS.NAME_EXISTS", message: "A prompt named support/greeting already exists" }));
    api.listPrompts.mockResolvedValue({ items: [promptSummary({ id: "p-9" })], page: 1, limit: 50, total: 1 });
    renderModal();
    fillValid();

    create();

    expect(await screen.findByText(/A prompt named support\/greeting exists\./)).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open it" }).getAttribute("href")).toBe("/orgs/acme/projects/default/prompts/p-9");
    expect(api.listPrompts).toHaveBeenCalledTimes(1);
    expect(api.listPrompts).toHaveBeenCalledWith({ name: "support/greeting" });
  });

  // Bug: a lookup that finds nothing, or fails, leaves the reader with no message at all.
  it.each([
    ["finds nothing", () => api.listPrompts.mockResolvedValue({ items: [], page: 1, limit: 50, total: 0 })],
    ["fails", () => api.listPrompts.mockRejectedValue(new Error("offline"))],
  ])("shows the server's error when the lookup of a taken name %s", async (_, lookup) => {
    api.createPrompt.mockRejectedValue(new ApiError(409, { code: "PROMPTS.NAME_EXISTS", message: "A prompt named support/greeting already exists" }));
    lookup();
    renderModal();
    fillValid();

    create();

    expect((await screen.findByRole("alert")).textContent).toBe("PROMPTS.NAME_EXISTS: A prompt named support/greeting already exists");
  });

  // Bug: any other refusal swallowed.
  it("shows any other error", async () => {
    api.createPrompt.mockRejectedValue(new ApiError(400, { code: "PROMPTS.INVALID_BODY", message: "Invalid prompt body: body is empty" }));
    renderModal();
    fillValid();

    create();

    expect((await screen.findByRole("alert")).textContent).toBe("PROMPTS.INVALID_BODY: Invalid prompt body: body is empty");
    expect(api.listPrompts).not.toHaveBeenCalled();
  });

  // Bug: the form refuses a name the API accepts. Another prompt tool allows inner spaces, so an imported name has them.
  it("accepts a name with a space and sends it trimmed", async () => {
    api.createPrompt.mockResolvedValue(promptDetail());
    renderModal();
    type("Name", "  Support Agent/first reply  ");
    type("Prompt text", "Hi");

    create();

    await waitFor(() => expect(api.createPrompt).toHaveBeenCalledWith(expect.objectContaining({ name: "Support Agent/first reply" })));
  });

  // Bug: the screen opened from a folder makes the reader type the folder again, and Cancel loses the folder.
  it("starts the name in the folder it was opened from, and Cancel returns there without sending", async () => {
    renderModal("?folder=support");

    expect((screen.getByRole("textbox", { name: "Name" }) as HTMLInputElement).value).toBe("support/");
    click("Cancel");

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts?folder=support"));
    expect(api.createPrompt).not.toHaveBeenCalled();
  });

  it("returns to the list root on Cancel when opened there", async () => {
    renderModal();

    click("Cancel");

    await waitFor(() => expect(currentLocation()).toBe("/orgs/acme/projects/default/prompts"));
  });
});
