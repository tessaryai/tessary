// SPDX-License-Identifier: Apache-2.0
import type { FocusEvent } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { closeOnFocusOut, itemsOf } from "./menuKeys";

function panelWith(markup: string): HTMLElement {
  const panel = document.createElement("div");
  panel.innerHTML = markup;
  document.body.append(panel);
  return panel;
}

const focusMovingTo = (next: Node | null) => ({ relatedTarget: next }) as unknown as FocusEvent;

afterEach(() => {
  document.body.replaceChildren();
});

describe("itemsOf", () => {
  // Catches a lifted helper that loses document order, which would send ArrowDown to the wrong item.
  it("itemsOfListsTheRoleInDocumentOrder", () => {
    const panel = panelWith(
      '<button role="menuitem">Edit</button><hr role="separator"><div><button role="menuitem">Move later</button></div><button role="option">Not a menu item</button>',
    );

    expect(itemsOf(panel, "menuitem").map((el) => el.textContent)).toEqual(["Edit", "Move later"]);
    expect(itemsOf(null, "menuitem")).toEqual([]);
  });
});

describe("closeOnFocusOut", () => {
  // Catches a menu that closes when focus moves between its own items, or that closes on a click into nowhere
  // and so swallows the click on a non-focusable option.
  it("closeOnFocusOutClosesOnlyWhenFocusLeavesTheContainer", () => {
    const container = panelWith('<button>Inside</button>');
    const outside = panelWith("<button>Outside</button>");
    const close = vi.fn();
    const onBlur = closeOnFocusOut({ current: container }, close);

    onBlur(focusMovingTo(container.querySelector("button")));
    onBlur(focusMovingTo(null));
    expect(close).not.toHaveBeenCalled();

    onBlur(focusMovingTo(outside.querySelector("button")));
    expect(close).toHaveBeenCalledTimes(1);

    closeOnFocusOut({ current: null }, close)(focusMovingTo(outside));
    expect(close).toHaveBeenCalledTimes(1);
  });
});
