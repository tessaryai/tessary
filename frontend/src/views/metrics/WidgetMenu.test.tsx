// SPDX-License-Identifier: Apache-2.0
/*
 * The per-widget menu in edit mode: the keyboard alternative to dragging (Move earlier, Move later), Edit and Remove,
 * the APG menu keys, and the attribute that keeps a click inside it from starting a drag.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { WidgetMenu } from "./WidgetMenu";

function renderMenu(over: { canEarlier?: boolean; canLater?: boolean } = {}) {
  const handlers = { onEdit: vi.fn(), onEarlier: vi.fn(), onLater: vi.fn(), onRemove: vi.fn() };
  render(
    <>
      <WidgetMenu title="Spend" canEarlier={over.canEarlier ?? true} canLater={over.canLater ?? true} {...handlers} />
      <button type="button">Outside</button>
    </>,
  );
  return { trigger: screen.getByRole("button", { name: "Spend actions" }), ...handlers };
}

const item = (name: string) => screen.getByRole("menuitem", { name });

describe("WidgetMenu", () => {
  // Bug: a move offered at the end of the dashboard, a missing action, or Remove run together with the moves.
  it("offersTheFourActionsWithASeparatorAndDisablesTheEnds", () => {
    const { trigger } = renderMenu({ canEarlier: false });
    expect(trigger.getAttribute("aria-haspopup")).toBe("menu");
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("menu")).toBeNull();

    fireEvent.click(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    const menu = screen.getByRole("menu", { name: "Spend actions" });
    expect(within(menu).getAllByRole("menuitem").map((i) => i.textContent)).toEqual([
      "Edit", "Move earlier", "Move later", "Remove",
    ]);
    expect(within(menu).getByRole("separator")).toBeTruthy();
    expect((item("Move earlier") as HTMLButtonElement).disabled).toBe(true);
    expect((item("Move later") as HTMLButtonElement).disabled).toBe(false);
    expect(item("Remove").className).toContain("text-fg-secondary");
  });

  // Bug: the arrows stop on a disabled item, Home and End go nowhere, or Escape leaves focus on a removed menu.
  it("arrowKeysHomeEndAndEscapeMoveFocusAndClose", () => {
    const { trigger } = renderMenu({ canLater: false });
    fireEvent.click(trigger);
    const press = (key: string) => fireEvent.keyDown(document.activeElement!, { key });
    expect(document.activeElement).toBe(item("Edit"));

    press("ArrowDown");
    expect(document.activeElement).toBe(item("Move earlier"));
    press("ArrowDown");
    expect(document.activeElement).toBe(item("Remove"));
    press("ArrowDown");
    expect(document.activeElement).toBe(item("Edit"));
    press("ArrowUp");
    expect(document.activeElement).toBe(item("Remove"));
    press("Home");
    expect(document.activeElement).toBe(item("Edit"));
    press("ArrowUp");
    expect(document.activeElement).toBe(item("Remove"));
    press("End");
    expect(document.activeElement).toBe(item("Remove"));
    expect(press("Tab")).toBe(true);

    press("Escape");
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(trigger);

    fireEvent.click(trigger);
    fireEvent.blur(item("Edit"), { relatedTarget: screen.getByRole("button", { name: "Outside" }) });
    expect(screen.queryByRole("menu")).toBeNull();
    fireEvent.click(trigger);
    fireEvent.click(trigger);
    expect(screen.queryByRole("menu")).toBeNull();
  });

  // Bug: an action that leaves the menu open over the dashboard, or calls the wrong handler.
  it("choosingAnActionCallsItAndCloses", () => {
    const { trigger, onEdit, onEarlier, onLater, onRemove } = renderMenu();
    const choose = (name: string) => {
      fireEvent.click(trigger);
      fireEvent.click(item(name));
      expect(screen.queryByRole("menu")).toBeNull();
    };

    choose("Move later");
    expect(onLater).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(trigger);
    choose("Move earlier");
    expect(onEarlier).toHaveBeenCalledTimes(1);
    choose("Edit");
    expect(onEdit).toHaveBeenCalledTimes(1);
    choose("Remove");
    expect(onRemove).toHaveBeenCalledTimes(1);
    expect(onLater).toHaveBeenCalledTimes(1);
  });

  // Bug: a mousedown on an open menu item starts a drag, because only the trigger was excluded from the drag start.
  it("theRootCarriesDataNoDrag", () => {
    const { trigger } = renderMenu();
    fireEvent.click(trigger);

    const root = trigger.closest("[data-no-drag]");
    expect(root).not.toBeNull();
    expect(root).not.toBe(trigger);
    expect(root!.contains(screen.getByRole("menu"))).toBe(true);
  });
});
