// SPDX-License-Identifier: Apache-2.0
/*
 * Settings → PII redaction. These are the exact rules the write path applies before trace content is
 * stored, so the bugs worth catching are the ones that change what gets redacted: a built-in rule
 * offered for edit or deletion, a toggle that sends the state it already has, a delete that skips its
 * confirmation, an edit that creates a second rule, and a preview that tests something other than
 * what the reader typed.
 */
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RedactionRuleView } from "../../api/types";
import { pending, renderRoute } from "../../test/render";
import { PiiRedaction } from "./PiiRedaction";

const api = vi.hoisted(() => ({
  base: "/api/orgs/acme/projects/default",
  listRedactionRules: vi.fn(),
  createRedactionRule: vi.fn(),
  updateRedactionRule: vi.fn(),
  setRedactionRuleEnabled: vi.fn(),
  deleteRedactionRule: vi.fn(),
  previewRedaction: vi.fn(),
}));

vi.mock("../../tenant/TenantContext", () => ({ useProjectApi: () => api }));

const rule = (over: Partial<RedactionRuleView>): RedactionRuleView => ({
  id: "r-1",
  name: "Email",
  pattern: "[\\\\w.]+@[\\\\w.]+",
  replacement: "[EMAIL]",
  built_in: false,
  enabled: true,
  sort_order: 0,
  created_at: "",
  updated_at: "",
  ...over,
});

let confirmReply = true;
beforeEach(() => {
  confirmReply = true;
  vi.spyOn(window, "confirm").mockImplementation(() => confirmReply);
  api.listRedactionRules.mockResolvedValue({
    rules: [rule({ id: "b-1", name: "SSN", built_in: true, enabled: false, pattern: "\\\\d{3}-\\\\d{2}-\\\\d{4}", replacement: "[SSN]" }), rule({})],
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)));
const row = (name: string) => screen.getByText(name).closest<HTMLElement>("div.flex.items-center.gap-3")!;

describe("the rule ledger", () => {
  it("lets a built-in rule be switched but never edited or deleted, and flips the state it shows", async () => {
    api.setRedactionRuleEnabled.mockResolvedValue({});
    renderRoute(<PiiRedaction />);

    const builtIn = row(await screen.findByText("SSN").then(() => "SSN"));
    expect(within(builtIn).getByText("Built-in")).toBeTruthy();
    expect(within(builtIn).getByText("Disabled")).toBeTruthy();
    expect(within(builtIn).queryByRole("button", { name: "Edit" })).toBeNull();
    expect(within(builtIn).queryByRole("button", { name: "Delete" })).toBeNull();

    fireEvent.click(within(builtIn).getByRole("button", { name: "Enable" }));
    await waitFor(() => expect(api.setRedactionRuleEnabled).toHaveBeenCalledWith("b-1", true));
    fireEvent.click(within(row("Email")).getByRole("button", { name: "Disable" }));
    await waitFor(() => expect(api.setRedactionRuleEnabled).toHaveBeenLastCalledWith("r-1", false));
  });

  it("deletes a custom rule only after confirmation, and names each refusal", async () => {
    api.deleteRedactionRule.mockRejectedValueOnce(new Error("in use")).mockResolvedValue(null);
    api.setRedactionRuleEnabled.mockRejectedValue(new Error("locked"));
    renderRoute(<PiiRedaction />);
    const custom = row(await screen.findByText("Email").then(() => "Email"));

    confirmReply = false;
    fireEvent.click(within(custom).getByRole("button", { name: "Delete" }));
    await settle();
    expect(api.deleteRedactionRule).not.toHaveBeenCalled();

    confirmReply = true;
    fireEvent.click(within(custom).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Could not delete rule")).toBeTruthy();
    fireEvent.click(within(custom).getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("Rule deleted")).toBeTruthy();
    expect(api.deleteRedactionRule).toHaveBeenLastCalledWith("r-1");

    fireEvent.click(within(custom).getByRole("button", { name: "Disable" }));
    expect(await screen.findByText("Could not update rule")).toBeTruthy();
  });

  it("shows the read loading and failing", async () => {
    api.listRedactionRules.mockImplementation(pending);
    renderRoute(<PiiRedaction />);
    expect(await screen.findByText("Loading rules…")).toBeTruthy();
    cleanup();

    api.listRedactionRules.mockRejectedValue(new Error("rules unavailable"));
    renderRoute(<PiiRedaction />);
    expect(await screen.findByText("rules unavailable")).toBeTruthy();
  });
});

describe("writing a rule", () => {
  it("creates a new rule from the dialog, with the name trimmed", async () => {
    api.createRedactionRule.mockResolvedValue({});
    renderRoute(<PiiRedaction />);
    fireEvent.click(await screen.findByRole("button", { name: "New rule" }));

    const dialog = screen.getByRole("dialog");
    const create = within(dialog).getByRole("button", { name: "Create rule" }) as HTMLButtonElement;
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), { target: { value: "  Account id " } });
    expect(create.disabled).toBe(true);
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Pattern" }), { target: { value: "ACC-\\\\d{6}" } });
    fireEvent.click(create);

    await waitFor(() => expect(api.createRedactionRule).toHaveBeenCalledWith({ name: "Account id", pattern: "ACC-\\\\d{6}", replacement: "[REDACTED]" }));
    expect(await screen.findByText("Rule created")).toBeTruthy();
    expect(api.updateRedactionRule).not.toHaveBeenCalled();
  });

  it("edits the rule it was opened on, rather than creating another", async () => {
    api.updateRedactionRule.mockResolvedValue({});
    renderRoute(<PiiRedaction />);
    fireEvent.click(within(row(await screen.findByText("Email").then(() => "Email"))).getByRole("button", { name: "Edit" }));

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("heading", { name: "Edit rule" })).toBeTruthy();
    expect((within(dialog).getByRole("textbox", { name: "Replacement" }) as HTMLInputElement).value).toBe("[EMAIL]");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Replacement" }), { target: { value: "[MAIL]" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(api.updateRedactionRule).toHaveBeenCalledWith("r-1", { name: "Email", pattern: "[\\\\w.]+@[\\\\w.]+", replacement: "[MAIL]" }));
    expect(await screen.findByText("Rule updated")).toBeTruthy();
    expect(api.createRedactionRule).not.toHaveBeenCalled();
  });

  it("keeps the dialog open and says why a save was refused", async () => {
    api.createRedactionRule.mockRejectedValue(new Error("invalid regex"));
    renderRoute(<PiiRedaction />);
    fireEvent.click(await screen.findByRole("button", { name: "New rule" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), { target: { value: "Bad" } });
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Pattern" }), { target: { value: "(" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create rule" }));

    expect(await screen.findByText("Could not save rule")).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("the playground", () => {
  it("previews the typed pattern, or the whole active rule set when it is blank", async () => {
    api.previewRedaction
      .mockResolvedValueOnce({ original: "", redacted: "SSN [X]", matches: 1 })
      .mockResolvedValueOnce({ original: "", redacted: "[EMAIL] and [SSN]", matches: 2 })
      .mockRejectedValueOnce(new Error("regex too slow"));
    renderRoute(<PiiRedaction />);
    await screen.findByText("Email");

    const preview = screen.getByRole("button", { name: "Preview redaction" }) as HTMLButtonElement;
    expect(preview.disabled).toBe(true);
    fireEvent.change(screen.getByRole("textbox", { name: "Sample text" }), { target: { value: "SSN 123-45-6789" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Pattern (optional)" }), { target: { value: "\\\\d{3}-\\\\d{2}-\\\\d{4}" } });
    fireEvent.change(screen.getAllByRole("textbox", { name: "Replacement" })[0], { target: { value: "[X]" } });
    fireEvent.click(preview);

    await waitFor(() => expect(api.previewRedaction).toHaveBeenCalledWith({ pattern: "\\\\d{3}-\\\\d{2}-\\\\d{4}", replacement: "[X]", sample_text: "SSN 123-45-6789" }));
    expect(await screen.findByText("1 match redacted")).toBeTruthy();
    expect(screen.getByText("SSN [X]")).toBeTruthy();

    fireEvent.change(screen.getByRole("textbox", { name: "Pattern (optional)" }), { target: { value: "  " } });
    fireEvent.click(preview);
    await waitFor(() => expect(api.previewRedaction).toHaveBeenLastCalledWith({ sample_text: "SSN 123-45-6789" }));
    expect(await screen.findByText("2 matches redacted")).toBeTruthy();

    fireEvent.click(preview);
    expect(await screen.findByText("Preview failed")).toBeTruthy();
  });
});
