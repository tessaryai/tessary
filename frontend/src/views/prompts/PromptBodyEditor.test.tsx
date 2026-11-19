// SPDX-License-Identifier: Apache-2.0
/*
 * The body editor is shared by the new-prompt and new-version modals and the playground. The bugs worth catching are
 * an edit applied to the wrong row, a move that loses or duplicates an item, a role the API wrote silently replaced,
 * and a validation message that never reaches the row it is about.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PromptChatBody } from "../../api/types";
import { NO_BODY_ERRORS, PromptBodyEditor, validateBody } from "./PromptBodyEditor";

const CHAT: PromptChatBody = [
  { role: "system", content: "Be {{tone}}." },
  { type: "placeholder", name: "history" },
  { role: "user", content: "Hi" },
];

function chat(value: PromptChatBody = CHAT, errors = NO_BODY_ERRORS) {
  const onChange = vi.fn();
  render(<PromptBodyEditor type="chat" value={value} onChange={onChange} errors={errors} />);
  return onChange;
}

describe("text body", () => {
  // Bug: the text box shows a stale value or reports its change somewhere else.
  it("edits the prompt text", () => {
    const onChange = vi.fn();
    render(<PromptBodyEditor type="text" value="Hi {{name}}" onChange={onChange} errors={{ body: "Enter the prompt text.", rows: [] }} />);

    const box = screen.getByRole("textbox", { name: "Prompt text" }) as HTMLTextAreaElement;
    expect(box.value).toBe("Hi {{name}}");
    expect(screen.getByText("Enter the prompt text.")).toBeTruthy();
    fireEvent.change(box, { target: { value: "Hello" } });

    expect(onChange).toHaveBeenCalledWith("Hello");
  });
});

describe("chat body", () => {
  // Bug: a placeholder drawn as a message, or a message's role and content read from the wrong entry.
  it("draws a message row with role and content, and a placeholder row with its name", () => {
    chat();

    expect((screen.getByRole("combobox", { name: "Role of message 1" }) as HTMLSelectElement).value).toBe("system");
    expect((screen.getByRole("textbox", { name: "Content of message 1" }) as HTMLTextAreaElement).value).toBe("Be {{tone}}.");
    expect((screen.getByRole("textbox", { name: "Placeholder name 2" }) as HTMLInputElement).value).toBe("history");
    expect(screen.queryByRole("combobox", { name: "Role of message 2" })).toBeNull();
  });

  // Bug: a role the API wrote (developer) shown as system and rewritten on the next save.
  it("keeps a role outside the three offered", () => {
    chat([{ role: "developer", content: "x" }]);

    expect((screen.getByRole("combobox", { name: "Role of message 1" }) as HTMLSelectElement).value).toBe("developer");
  });

  // Bug: an edit applied to the first row, or a new item that drops the existing ones.
  it.each([
    ["changes a role", () => fireEvent.change(screen.getByRole("combobox", { name: "Role of message 3" }), { target: { value: "assistant" } }),
      [CHAT[0], CHAT[1], { role: "assistant", content: "Hi" }]],
    ["changes content", () => fireEvent.change(screen.getByRole("textbox", { name: "Content of message 3" }), { target: { value: "Yo" } }),
      [CHAT[0], CHAT[1], { role: "user", content: "Yo" }]],
    ["renames a placeholder", () => fireEvent.change(screen.getByRole("textbox", { name: "Placeholder name 2" }), { target: { value: "past" } }),
      [CHAT[0], { type: "placeholder", name: "past" }, CHAT[2]]],
    ["adds a message", () => fireEvent.click(screen.getByRole("button", { name: "Add message" })),
      [...CHAT, { role: "user", content: "" }]],
    ["adds a placeholder", () => fireEvent.click(screen.getByRole("button", { name: "Add placeholder" })),
      [...CHAT, { type: "placeholder", name: "" }]],
    ["moves an item up", () => fireEvent.click(screen.getByRole("button", { name: "Move item 2 up" })),
      [CHAT[1], CHAT[0], CHAT[2]]],
    ["moves an item down", () => fireEvent.click(screen.getByRole("button", { name: "Move item 2 down" })),
      [CHAT[0], CHAT[2], CHAT[1]]],
    ["removes an item", () => fireEvent.click(screen.getByRole("button", { name: "Remove item 1" })),
      [CHAT[1], CHAT[2]]],
  ])("%s", (_, act, expected) => {
    const onChange = chat();
    act();
    expect(onChange).toHaveBeenCalledWith(expected);
  });

  // Bug: the first item moved up or the last moved down, off the end of the list.
  it("disables moving the first item up and the last down", () => {
    chat();

    expect((screen.getByRole("button", { name: "Move item 1 up" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Move item 3 down" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Move item 1 down" }) as HTMLButtonElement).disabled).toBe(false);
  });

  // Bug: a row's error shown under another row, or the list error never shown.
  it("shows each error where it applies", () => {
    chat(CHAT, { body: "Add at least one message.", rows: [null, null, "Enter the message content."] });

    expect(screen.getByText("Add at least one message.")).toBeTruthy();
    expect(screen.getByText("Enter the message content.")).toBeTruthy();
  });
});

describe("validateBody", () => {
  // Bug: a blank prompt sent, or whitespace accepted as text.
  it("requires text", () => {
    expect(validateBody("   ")).toEqual({ body: "Enter the prompt text.", rows: [] });
    expect(validateBody("Hi")).toEqual(NO_BODY_ERRORS);
  });

  // Bug: a chat body of placeholders only, a blank message, or a dotted placeholder name sent to the server.
  it("requires a message, content in each message, and a valid placeholder name", () => {
    expect(validateBody([{ type: "placeholder", name: "history" }])).toEqual({ body: "Add at least one message.", rows: [null] });
    expect(validateBody([{ role: "user", content: " " }, { type: "placeholder", name: "a.b" }, { type: "placeholder", name: "ok_1" }])).toEqual({
      body: null,
      rows: [
        "Enter the message content.",
        "A placeholder name starts with a letter or _ and has only letters, digits and _.",
        null,
      ],
    });
  });
});
