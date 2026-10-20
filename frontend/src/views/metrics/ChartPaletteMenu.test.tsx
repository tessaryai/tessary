// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ChartPaletteProvider } from "../../ui/chartPalette";
import { ChartPaletteMenu } from "./ChartPaletteMenu";

// render.tsx wraps only the query client and toasts, and the palette hook is inert without its provider.
function renderMenu() {
  return render(
    <ChartPaletteProvider>
      <ChartPaletteMenu />
    </ChartPaletteProvider>,
  );
}

const trigger = () => screen.getByRole("button", { name: "Chart colors" });
const palettes = () => screen.getByRole("radiogroup", { name: "Chart palette" });
const row = (name: string) => within(palettes()).getByRole("radio", { name });

// setup.ts clears neither storage nor the <html> attribute, so a pick would leak into the next test.
afterEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.chartPalette;
});

describe("ChartPaletteMenu", () => {
  // Catches a menu that opens unasked, or a pick that does not reach storage or the CSS attribute, or that the next
  // visit forgets.
  it("opensFromTheDotsAndRemembersThePick", () => {
    const first = renderMenu();

    expect(trigger().getAttribute("aria-expanded")).toBe("false");
    expect(trigger().textContent).toBe("");
    expect(screen.queryByRole("dialog", { name: "Chart colors" })).toBeNull();

    fireEvent.click(trigger());

    expect(trigger().getAttribute("aria-expanded")).toBe("true");
    expect(within(palettes()).getAllByRole("radio").map((r) => r.textContent)).toEqual([
      "Tessary", "Colorblind safe", "Greyscale",
    ]);
    expect(row("Tessary").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(row("Tessary"));

    fireEvent.click(row("Colorblind safe"));

    expect(row("Colorblind safe").getAttribute("aria-checked")).toBe("true");
    expect(row("Tessary").getAttribute("aria-checked")).toBe("false");
    expect(row("Colorblind safe").tabIndex).toBe(0);
    expect(row("Tessary").tabIndex).toBe(-1);
    expect(window.localStorage.getItem("tessary.prefs.chartPalette")).toBe("colorblind");
    expect(document.documentElement.dataset.chartPalette).toBe("colorblind");

    first.unmount();
    renderMenu();
    fireEvent.click(trigger());
    expect(row("Colorblind safe").getAttribute("aria-checked")).toBe("true");
  });

  // Catches a radio group a keyboard reader cannot move through, or one that stops at either end.
  it("arrowKeysMoveAndSelectTheNextRow", () => {
    renderMenu();
    fireEvent.click(trigger());

    fireEvent.keyDown(row("Tessary"), { key: "ArrowDown" });
    expect(row("Colorblind safe").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(row("Colorblind safe"));

    fireEvent.keyDown(row("Colorblind safe"), { key: "ArrowRight" });
    fireEvent.keyDown(row("Greyscale"), { key: "ArrowDown" });
    expect(row("Tessary").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(row("Tessary"));

    fireEvent.keyDown(row("Tessary"), { key: "ArrowUp" });
    expect(row("Greyscale").getAttribute("aria-checked")).toBe("true");

    fireEvent.keyDown(row("Colorblind safe"), { key: " " });
    expect(row("Colorblind safe").getAttribute("aria-checked")).toBe("true");
  });

  // Catches a menu that traps the reader: Escape and a click outside both close it, and Escape returns focus.
  it("escapeAndAnOutsideClickClose", () => {
    renderMenu();
    fireEvent.click(trigger());

    fireEvent.keyDown(row("Tessary"), { key: "Escape" });
    expect(screen.queryByRole("dialog", { name: "Chart colors" })).toBeNull();
    expect(document.activeElement).toBe(trigger());

    fireEvent.click(trigger());
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole("dialog", { name: "Chart colors" })).toBeNull();
  });
});
