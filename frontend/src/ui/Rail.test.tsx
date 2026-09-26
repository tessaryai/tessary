// SPDX-License-Identifier: Apache-2.0
/*
 * The right rail, its focus trap, and the ESC stack it shares with every overlay. The bugs worth
 * catching: ESC closing a rail under another overlay (whose close may navigate), Tab escaping the rail
 * or focus not coming back when it closes, and a width that is not remembered, not clamped, or lost to
 * storage that refuses.
 */
import { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Rail } from "./Rail";

const KEY = "tsy-rail-width";

beforeEach(() => {
  // jsdom lays nothing out, so every element reads as hidden; the trap only cycles through visible ones.
  Object.defineProperty(HTMLElement.prototype, "offsetParent", {
    configurable: true,
    get(this: HTMLElement) {
      return this.hasAttribute("hidden") ? null : this.parentNode;
    },
  });
  window.innerWidth = 2000;
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.localStorage.clear();
  delete (HTMLElement.prototype as { offsetParent?: unknown }).offsetParent;
});

function Opener({ onClose = () => {}, children }: { onClose?: () => void; children?: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      <Rail
        open={open}
        onClose={() => {
          onClose();
          setOpen(false);
        }}
        title="Trace detail"
        meta="3 spans"
      >
        {children ?? (
          <>
            <a href="#a">First</a>
            <button type="button">Last</button>
            <button type="button" hidden>
              Hidden
            </button>
          </>
        )}
      </Rail>
    </>
  );
}

const rail = () => screen.getByRole("dialog", { name: "Trace detail" });
const width = () => Number.parseInt(rail().style.width, 10);

describe("Rail", () => {
  it("renders nothing while closed, and its title, meta, and body when open", () => {
    render(<Opener />);
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Open" }));

    expect(rail().textContent).toContain("3 spans");
    expect(screen.getByRole("link", { name: "First" })).toBeTruthy();
  });

  it("closes on its button, and on ESC but not on any other key", () => {
    const onClose = vi.fn();
    render(<Opener onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));

    fireEvent.keyDown(window, { key: "Enter" });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("closes only the topmost overlay on ESC, and leaves one another listener already took", () => {
    const under = vi.fn();
    const over = vi.fn();
    render(
      <>
        <Rail open onClose={under} title="Under">
          body
        </Rail>
        <Rail open onClose={over} title="Over">
          body
        </Rail>
      </>,
    );

    fireEvent.keyDown(window, { key: "Escape" });
    expect(over).toHaveBeenCalledTimes(1);
    expect(under).not.toHaveBeenCalled();

    const taken = new KeyboardEvent("keydown", { key: "Escape", cancelable: true });
    taken.preventDefault();
    window.dispatchEvent(taken);
    expect(over).toHaveBeenCalledTimes(1);
  });

  it("offers expand only when there is somewhere to expand to", () => {
    const onExpand = vi.fn();
    const { rerender } = render(
      <Rail open onClose={() => {}} title="Trace detail">
        body
      </Rail>,
    );
    expect(screen.queryByRole("button", { name: "Expand" })).toBeNull();

    rerender(
      <Rail open onClose={() => {}} title="Trace detail" onExpand={onExpand} expandLabel="Open full trace">
        body
      </Rail>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Open full trace" }));
    expect(onExpand).toHaveBeenCalled();
  });
});

describe("the rail's focus", () => {
  it("moves focus in on open, keeps Tab inside, and hands focus back on close", () => {
    render(<Opener />);
    const opener = screen.getByRole("button", { name: "Open" });
    opener.focus();
    fireEvent.click(opener);

    const items = Array.from(rail().querySelectorAll<HTMLElement>("a[href], button:not([hidden])"));
    const [first, last] = [items[0], items[items.length - 1]];
    expect(document.activeElement).toBe(first);

    last.focus();
    fireEvent.keyDown(rail(), { key: "Tab" });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(rail(), { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);

    // Mid-list Tab is the browser's to move; the trap leaves it alone.
    items[1].focus();
    const tab = new KeyboardEvent("keydown", { key: "Tab", bubbles: true, cancelable: true });
    rail().dispatchEvent(tab);
    expect(tab.defaultPrevented).toBe(false);
    fireEvent.keyDown(rail(), { key: "a" });
    expect(document.activeElement).toBe(items[1]);

    fireEvent.keyDown(window, { key: "Escape" });
    expect(document.activeElement).toBe(opener);
  });

  it("pulls Shift+Tab back in from outside the rail", () => {
    render(
      <>
        <button type="button">Outside</button>
        <Rail open onClose={() => {}} title="Trace detail">
          <a href="#a">Only</a>
        </Rail>
      </>,
    );
    screen.getByRole("button", { name: "Outside" }).focus();

    fireEvent.keyDown(rail(), { key: "Tab", shiftKey: true });

    expect(document.activeElement).toBe(screen.getByRole("link", { name: "Only" }));
  });

  it("holds focus on the rail itself when nothing in it can take focus", () => {
    render(
      <Rail open onClose={() => {}} title="Trace detail">
        plain text
      </Rail>,
    );
    for (const b of rail().querySelectorAll("button")) b.setAttribute("disabled", "");

    fireEvent.keyDown(rail(), { key: "Tab" });

    expect(document.activeElement).toBe(rail());
  });
});

describe("the rail's width", () => {
  it("opens at 44% of the window, clamped to its 520px floor", () => {
    render(
      <Rail open onClose={() => {}} title="Trace detail">
        body
      </Rail>,
    );
    expect(width()).toBe(880);
    cleanup();

    window.innerWidth = 800;
    render(
      <Rail open onClose={() => {}} title="Trace detail">
        body
      </Rail>,
    );
    expect(width()).toBe(520);
  });

  it("opens at the width last dragged to, even in another rail, clamped to 85% of the window", () => {
    render(<Opener />);
    // Stored after this rail mounted, as a drag in another rail would.
    window.localStorage.setItem(KEY, "700");
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(width()).toBe(700);
    cleanup();

    window.localStorage.setItem(KEY, "5000");
    render(<Opener />);
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(width()).toBe(1700);
  });

  it("grows when its edge is dragged left, stops at the floor, and remembers where it was let go", () => {
    render(
      <Rail open onClose={() => {}} title="Trace detail">
        body
      </Rail>,
    );
    const handle = screen.getByRole("separator", { name: "Resize panel" });

    fireEvent.pointerDown(handle, { clientX: 1000 });
    fireEvent.pointerMove(window, { clientX: 900 });
    expect(width()).toBe(980);
    fireEvent.pointerMove(window, { clientX: 1900 });
    expect(width()).toBe(520);
    fireEvent.pointerMove(window, { clientX: 950 });
    fireEvent.pointerUp(window);

    expect(window.localStorage.getItem(KEY)).toBe("930");
    fireEvent.pointerMove(window, { clientX: 0 });
    expect(width()).toBe(930);
  });

  it("works on, unremembered, when storage refuses", () => {
    const storage = window.localStorage;
    vi.spyOn(storage, "getItem").mockImplementation(() => {
      throw new Error("denied");
    });
    const setItem = vi.spyOn(storage, "setItem").mockImplementation(() => {
      throw new Error("denied");
    });
    render(
      <Rail open onClose={() => {}} title="Trace detail">
        body
      </Rail>,
    );
    expect(width()).toBe(880);

    const handle = screen.getByRole("separator", { name: "Resize panel" });
    fireEvent.pointerDown(handle, { clientX: 1000 });
    fireEvent.pointerMove(window, { clientX: 900 });
    fireEvent.pointerUp(window);

    expect(setItem).toHaveBeenCalled();
    expect(width()).toBe(980);
  });
});
