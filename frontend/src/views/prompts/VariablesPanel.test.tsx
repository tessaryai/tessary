// SPDX-License-Identifier: Apache-2.0
/*
 * The playground fills a template through this panel. The bugs worth catching are a variable with no input, a
 * typed value stored under another name, a placeholder offered as a variable, and an empty panel with no hint.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { VariablesPanel } from "./VariablesPanel";

const BODY = [
  { role: "system", content: "Be {{tone}} for {{brand}}." },
  { type: "placeholder" as const, name: "history" },
];

it("offers one input per variable and one messages box per placeholder", () => {
  const onChange = vi.fn();
  render(<VariablesPanel body={BODY} values={{ vars: { brand: "Acme" }, placeholders: {} }} onChange={onChange} />);

  expect((screen.getByRole("textbox", { name: "tone" }) as HTMLInputElement).value).toBe("");
  expect((screen.getByRole("textbox", { name: "brand" }) as HTMLInputElement).value).toBe("Acme");
  fireEvent.change(screen.getByRole("textbox", { name: "tone" }), { target: { value: "kind" } });
  expect(onChange).toHaveBeenLastCalledWith({ vars: { brand: "Acme", tone: "kind" }, placeholders: {} });

  fireEvent.change(screen.getByRole("textbox", { name: "Messages for history" }), { target: { value: "[]" } });
  expect(onChange).toHaveBeenLastCalledWith({ vars: { brand: "Acme" }, placeholders: { history: "[]" } });
});

it("keeps a placeholder's typed messages", () => {
  render(<VariablesPanel body={BODY} values={{ vars: {}, placeholders: { history: "[1]" } }} onChange={vi.fn()} />);

  expect((screen.getByRole("textbox", { name: "Messages for history" }) as HTMLTextAreaElement).value).toBe("[1]");
});

it("says how to add a variable when the body has none", () => {
  render(<VariablesPanel body="Plain text" values={{ vars: {}, placeholders: {} }} onChange={vi.fn()} />);

  expect(screen.getByText("No variables. Write {{name}} in the body to add one.")).toBeTruthy();
});
