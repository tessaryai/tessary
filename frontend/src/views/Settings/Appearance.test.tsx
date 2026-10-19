// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { ChartPaletteProvider } from "../../ui/chartPalette";
import { DensityProvider } from "../../ui/density";
import { AppearanceControls } from "./Appearance";

// render.tsx wraps only the query client and toasts, and the palette hook is inert without its provider.
function renderAppearance() {
  return render(
    <ChartPaletteProvider>
      <AppearanceControls />
    </ChartPaletteProvider>,
  );
}

const palettes = () => screen.getByRole("radiogroup", { name: "Chart palette" });
const card = (name: string) => within(palettes()).getByRole("radio", { name });

// setup.ts clears neither storage nor the <html> attribute, so a pick would leak into the next test.
afterEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.chartPalette;
});

describe("AppearanceControls chart palette", () => {
  // Catches a pick that does not reach storage or the CSS attribute, or that the next visit forgets.
  it("offersThreeChartPaletteCardsAndRemembersThePick", () => {
    const first = renderAppearance();

    expect(within(palettes()).getAllByRole("radio")).toHaveLength(3);
    expect(card("Tessary").getAttribute("aria-checked")).toBe("true");
    expect(card("Colorblind safe").getAttribute("aria-checked")).toBe("false");
    expect(card("Greyscale").getAttribute("aria-checked")).toBe("false");
    expect(card("Colorblind safe").getAttribute("aria-describedby")).toBeTruthy();
    expect(screen.getByText("Series stay apart for the common kinds of color blindness.")).toBeTruthy();
    expect(
      screen.getByText("Colors for chart series. Status colors stay the same everywhere else. Saved in this browser."),
    ).toBeTruthy();

    fireEvent.click(card("Colorblind safe"));

    expect(card("Colorblind safe").getAttribute("aria-checked")).toBe("true");
    expect(card("Tessary").getAttribute("aria-checked")).toBe("false");
    expect(card("Colorblind safe").tabIndex).toBe(0);
    expect(card("Tessary").tabIndex).toBe(-1);
    expect(window.localStorage.getItem("tessary.prefs.chartPalette")).toBe("colorblind");
    expect(document.documentElement.dataset.chartPalette).toBe("colorblind");

    first.unmount();
    renderAppearance();
    expect(card("Colorblind safe").getAttribute("aria-checked")).toBe("true");
  });

  // Catches a radio group a keyboard reader cannot move through, or one that stops at either end.
  it("arrowKeysMoveAndSelectTheNextCard", () => {
    renderAppearance();

    fireEvent.keyDown(card("Tessary"), { key: "ArrowRight" });
    expect(card("Colorblind safe").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(card("Colorblind safe"));

    fireEvent.keyDown(card("Colorblind safe"), { key: "ArrowDown" });
    expect(card("Greyscale").getAttribute("aria-checked")).toBe("true");

    fireEvent.keyDown(card("Greyscale"), { key: "ArrowRight" });
    expect(card("Tessary").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(card("Tessary"));

    fireEvent.keyDown(card("Tessary"), { key: "ArrowLeft" });
    expect(card("Greyscale").getAttribute("aria-checked")).toBe("true");
    expect(document.activeElement).toBe(card("Greyscale"));

    fireEvent.keyDown(card("Greyscale"), { key: "ArrowUp" });
    expect(card("Colorblind safe").getAttribute("aria-checked")).toBe("true");

    fireEvent.keyDown(card("Tessary"), { key: "Enter" });
    expect(card("Tessary").getAttribute("aria-checked")).toBe("true");

    fireEvent.keyDown(card("Greyscale"), { key: " " });
    expect(card("Greyscale").getAttribute("aria-checked")).toBe("true");

    fireEvent.keyDown(card("Greyscale"), { key: "Tab" });
    expect(card("Greyscale").getAttribute("aria-checked")).toBe("true");
  });

  // Catches the default label drifting to another card or appearing on every card.
  it("theDefaultCardCarriesTheBadge", () => {
    renderAppearance();

    expect(within(card("Tessary")).getByText("Default")).toBeTruthy();
    expect(within(card("Colorblind safe")).queryByText("Default")).toBeNull();
    expect(within(card("Greyscale")).queryByText("Default")).toBeNull();
    expect(within(palettes()).getAllByTitle("Other")).toHaveLength(3);
  });

  // Catches the new row replacing or breaking the density control beside it.
  it("keepsTheDensityRow", () => {
    render(
      <DensityProvider>
        <ChartPaletteProvider>
          <AppearanceControls />
        </ChartPaletteProvider>
      </DensityProvider>,
    );

    const density = screen.getByRole("radiogroup", { name: "Density" });
    expect(within(density).getAllByRole("radio").map((r) => r.textContent)).toEqual(["Comfortable", "Compact"]);
    expect(screen.getByText("Row spacing across every data view.")).toBeTruthy();

    fireEvent.click(within(density).getByRole("radio", { name: "Compact" }));

    expect(within(density).getByRole("radio", { name: "Compact" }).getAttribute("aria-checked")).toBe("true");
    expect(window.localStorage.getItem("tessary.prefs.density")).toBe("compact");
    expect(card("Tessary").getAttribute("aria-checked")).toBe("true");
  });
});
