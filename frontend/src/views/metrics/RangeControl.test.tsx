// SPDX-License-Identifier: Apache-2.0
/*
 * The window picker. The bugs worth catching: a preset that waits for a second click, a custom panel that forgets the
 * applied pair, a reversed, blank or over-long pair that Apply still sends, a message that only appears after a click,
 * and an Escape that applies half-typed dates.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { RangeState } from "./metricsRules";
import { RangeControl } from "./RangeControl";

/** datetime-local values are local time, so expectations go through the same local parse the browser uses. */
const iso = (local: string) => new Date(local).toISOString();

function renderControl(range: RangeState = { kind: "preset", key: "7d" }) {
  const onChange = vi.fn();
  render(<RangeControl range={range} onChange={onChange} />);
  return onChange;
}

const window_ = () => screen.getByRole("group", { name: "Window" });
const panel = () => screen.queryByRole("group", { name: "Custom range" });
const from = () => screen.getByLabelText("From") as HTMLInputElement;
const to = () => screen.getByLabelText("To") as HTMLInputElement;
const apply = () => screen.getByRole("button", { name: "Apply" }) as HTMLButtonElement;
const openCustom = () => fireEvent.click(within(window_()).getByRole("button", { name: "Custom" }));

describe("RangeControl", () => {
  // Bug: a preset needs an Apply, or reports the wrong key.
  it("aPresetChangesAtOnce", () => {
    const onChange = renderControl();
    const buttons = within(window_()).getAllByRole("button");
    expect(buttons.map((b) => b.textContent)).toEqual(["24h", "7d", "30d", "90d", "Custom"]);
    expect(within(window_()).getByRole("button", { name: "7d" }).getAttribute("aria-pressed")).toBe("true");

    fireEvent.click(within(window_()).getByRole("button", { name: "30d" }));
    expect(onChange).toHaveBeenCalledWith({ kind: "preset", key: "30d" });
    expect(panel()).toBeNull();
  });

  // Bug: reopening the panel loses the applied window, or shows it in UTC where the inputs are local.
  it("customOpensThePanelSeededFromTheCurrentPair", () => {
    renderControl({ kind: "custom", from: iso("2026-10-01T09:00"), to: iso("2026-10-03T09:00") });
    expect(within(window_()).getByRole("button", { name: "Custom" }).getAttribute("aria-pressed")).toBe("true");
    expect(panel()).toBeNull();

    openCustom();
    const p = panel()!;
    expect(within(p).getByText("Custom range")).toBeTruthy();
    expect([from().value, to().value]).toEqual(["2026-10-01T09:00", "2026-10-03T09:00"]);
    expect(within(p).getAllByRole("button").map((b) => b.textContent)).toEqual(["Cancel", "Apply"]);
    expect(apply().disabled).toBe(false);
  });

  // Bug: a reversed pair is sent, or its message waits for a click on Apply.
  it("aReversedPairDisablesApplyAndSaysWhy", () => {
    renderControl();
    openCustom();
    fireEvent.change(from(), { target: { value: "2026-10-05T00:00" } });
    fireEvent.change(to(), { target: { value: "2026-10-04T00:00" } });
    expect(screen.getByText("The start must be before the end.")).toBeTruthy();
    expect(apply().disabled).toBe(true);
  });

  // Bug: a window the server refuses as too long is sent anyway.
  it("overNinetyDaysDisablesApplyAndSaysWhy", () => {
    renderControl();
    openCustom();
    fireEvent.change(from(), { target: { value: "2026-06-01T09:00" } });
    fireEvent.change(to(), { target: { value: "2026-10-18T09:00" } });
    expect(screen.getByText("Pick a window of 90 days or less.")).toBeTruthy();
    expect(to().getAttribute("aria-invalid")).toBe("true");
    expect(from().getAttribute("aria-invalid")).toBe("false");
    expect(apply().disabled).toBe(true);
  });

  // Bug: a fresh panel opens on a red message, or one blank side lets Apply through.
  it("aBlankSideAsksForBoth", () => {
    renderControl();
    openCustom();
    expect([from().value, to().value]).toEqual(["", ""]);
    expect(screen.queryByText("Pick a start and an end.")).toBeNull();
    expect(apply().disabled).toBe(true);

    fireEvent.change(from(), { target: { value: "2026-10-05T00:00" } });
    expect(screen.getByText("Pick a start and an end.")).toBeTruthy();
    expect(apply().disabled).toBe(true);
  });

  // Bug: Apply sends local strings instead of instants, or leaves the panel open.
  it("applyEmitsTheCustomWindowAndCloses", () => {
    const onChange = renderControl();
    openCustom();
    fireEvent.change(from(), { target: { value: "2026-10-01T09:00" } });
    fireEvent.change(to(), { target: { value: "2026-10-03T09:00" } });
    fireEvent.click(apply());
    expect(onChange).toHaveBeenCalledWith({ kind: "custom", from: iso("2026-10-01T09:00"), to: iso("2026-10-03T09:00") });
    expect(panel()).toBeNull();
  });

  // Bug: dismissing the panel applies what was typed.
  it("escapeClosesWithoutAChange", () => {
    const onChange = renderControl();
    openCustom();
    fireEvent.change(from(), { target: { value: "2026-10-01T09:00" } });
    fireEvent.change(to(), { target: { value: "2026-10-03T09:00" } });
    fireEvent.keyDown(document, { key: "Escape" });
    expect(panel()).toBeNull();

    openCustom();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(panel()).toBeNull();
    expect(onChange).not.toHaveBeenCalled();
  });
});
