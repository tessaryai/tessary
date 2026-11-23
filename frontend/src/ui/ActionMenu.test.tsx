// SPDX-License-Identifier: Apache-2.0
/*
 * The More actions menu. The bugs worth catching are a menu the keyboard cannot walk, a disabled action that takes
 * focus or runs, an Escape that leaves focus nowhere, and an action that runs while the menu stays open.
 */
import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactElement } from "react";
import { expect, it, vi } from "vitest";
import { ActionMenu } from "./ActionMenu";

function renderMenu(after?: ReactElement) {
  const rename = vi.fn();
  const archive = vi.fn();
  const remove = vi.fn();
  render(
    <ActionMenu
      label="More actions"
      items={[
        { label: "Rename", onSelect: rename },
        { label: "Archive", onSelect: archive, disabled: true },
        { label: "Delete", onSelect: remove, danger: true, icon: <span>x</span> },
      ]}
    />,
  );
  if (after) render(after);
  const trigger = screen.getByRole("button", { name: "More actions" });
  fireEvent.click(trigger);
  return { trigger, rename, archive, remove };
}

const focused = () => (document.activeElement as HTMLElement).textContent;

it("walks the enabled actions with the arrow keys, Home and End, and skips a disabled one", () => {
  renderMenu();
  const key = (k: string) => fireEvent.keyDown(document.activeElement as HTMLElement, { key: k });
  expect(focused()).toBe("Rename");

  key("ArrowDown");
  expect(focused()).toBe("xDelete");
  key("ArrowDown");
  expect(focused()).toBe("Rename");
  key("ArrowUp");
  expect(focused()).toBe("xDelete");
  key("Home");
  expect(focused()).toBe("Rename");
  key("End");
  expect(focused()).toBe("xDelete");
  key("a");
  expect(focused()).toBe("xDelete");
  expect((screen.getByRole("menuitem", { name: "Archive" }) as HTMLButtonElement).disabled).toBe(true);
});

it("closes on Escape and gives focus back to the button", () => {
  const { trigger } = renderMenu();

  fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });

  expect(screen.queryByRole("menu")).toBeNull();
  expect(document.activeElement).toBe(trigger);
  expect(trigger.getAttribute("aria-expanded")).toBe("false");
});

it("runs the chosen action once and closes, and a second click on the button closes too", () => {
  const { trigger, remove } = renderMenu();

  fireEvent.click(screen.getByRole("menuitem", { name: "xDelete" }));

  expect(remove).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole("menu")).toBeNull();
  fireEvent.click(trigger);
  expect(screen.getByRole("menu")).toBeTruthy();
  fireEvent.click(trigger);
  expect(screen.queryByRole("menu")).toBeNull();
});

// Bug: tabbing out of the open menu leaves it open over whatever the reader moved to.
it("closes when focus leaves it", () => {
  renderMenu(<button type="button">Elsewhere</button>);

  fireEvent.blur(screen.getByRole("menuitem", { name: "Rename" }), { relatedTarget: screen.getByRole("button", { name: "Elsewhere" }) });

  expect(screen.queryByRole("menu")).toBeNull();
});
