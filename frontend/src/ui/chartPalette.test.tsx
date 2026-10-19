// SPDX-License-Identifier: Apache-2.0
import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CHART_PALETTES, CHART_PALETTE_STORAGE_KEY, ChartPaletteProvider, useChartPalette } from "./chartPalette";
import type { ChartPalette } from "./chartPalette";

let pick: (p: ChartPalette) => void = () => {};

function Probe() {
  const { palette, setPalette } = useChartPalette();
  pick = setPalette;
  return <output aria-label="palette">{palette}</output>;
}

const shown = () => screen.getByLabelText("palette").textContent;
const stamped = () => document.documentElement.dataset.chartPalette;

// setup.ts clears neither storage nor the <html> attribute, so a pick would leak into the next test.
afterEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.chartPalette;
});

describe("ChartPaletteProvider", () => {
  // Catches a first visit with no palette on <html>, which leaves a Rail portal outside any palette scope.
  it("defaultsToTessaryAndStampsTheHtmlElement", () => {
    render(
      <ChartPaletteProvider>
        <Probe />
      </ChartPaletteProvider>,
    );

    expect(shown()).toBe("tessary");
    expect(stamped()).toBe("tessary");
    expect(CHART_PALETTES).toEqual([
      { value: "tessary", label: "Tessary" },
      { value: "colorblind", label: "Colorblind safe" },
      { value: "greyscale", label: "Greyscale" },
    ]);
  });

  // Catches a stale or hand-edited stored value reaching the picker, which has no card to check for it.
  it("readsAStoredChoiceAndIgnoresAnUnknownOne", () => {
    window.localStorage.setItem(CHART_PALETTE_STORAGE_KEY, "greyscale");
    const first = render(
      <ChartPaletteProvider>
        <Probe />
      </ChartPaletteProvider>,
    );
    expect(shown()).toBe("greyscale");
    expect(stamped()).toBe("greyscale");
    first.unmount();

    window.localStorage.setItem(CHART_PALETTE_STORAGE_KEY, "neon");
    render(
      <ChartPaletteProvider>
        <Probe />
      </ChartPaletteProvider>,
    );
    expect(shown()).toBe("tessary");
    expect(stamped()).toBe("tessary");
  });

  // Catches a pick that changes the screen but is forgotten on reload, or that never reaches the CSS.
  it("setPaletteWritesStorageAndTheAttribute", () => {
    window.localStorage.setItem("tessary.prefs.chartPalette", "tessary");
    render(
      <ChartPaletteProvider>
        <Probe />
      </ChartPaletteProvider>,
    );

    act(() => pick("colorblind"));

    expect(shown()).toBe("colorblind");
    expect(window.localStorage.getItem("tessary.prefs.chartPalette")).toBe("colorblind");
    expect(stamped()).toBe("colorblind");
  });

  // Catches a tree without the provider that crashes or writes a palette nobody chose.
  it("theHookOutsideAProviderIsInertAndTessary", () => {
    render(<Probe />);

    act(() => pick("greyscale"));

    expect(shown()).toBe("tessary");
    expect(window.localStorage.getItem(CHART_PALETTE_STORAGE_KEY)).toBeNull();
    expect(stamped()).toBeUndefined();
  });
});
