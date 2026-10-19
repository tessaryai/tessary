// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

/** A `data-chart-palette` attribute on <html> re-points the `--color-series-*` CSS vars (tokens.css). */
export type ChartPalette = "tessary" | "colorblind" | "greyscale";

export const CHART_PALETTES: { value: ChartPalette; label: string }[] = [
  { value: "tessary", label: "Tessary" },
  { value: "colorblind", label: "Colorblind safe" },
  { value: "greyscale", label: "Greyscale" },
];

export const CHART_PALETTE_STORAGE_KEY = "tessary.prefs.chartPalette";

type ChartPaletteCtx = {
  palette: ChartPalette;
  setPalette: (p: ChartPalette) => void;
};

const Ctx = createContext<ChartPaletteCtx>({
  palette: "tessary",
  setPalette: () => {},
});

function readStoredPalette(): ChartPalette {
  const v = window.localStorage.getItem(CHART_PALETTE_STORAGE_KEY);
  return CHART_PALETTES.find((p) => p.value === v)?.value ?? "tessary";
}

/** Stamps <html>, not a wrapper, because the widget editor's preview renders in a Rail portal on document.body. */
export function ChartPaletteProvider({ children }: { children: ReactNode }) {
  const [palette, setPaletteState] = useState<ChartPalette>(readStoredPalette);
  useEffect(() => {
    document.documentElement.dataset.chartPalette = palette;
  }, [palette]);
  const value = useMemo<ChartPaletteCtx>(
    () => ({
      palette,
      setPalette: (p: ChartPalette) => {
        setPaletteState(p);
        window.localStorage.setItem(CHART_PALETTE_STORAGE_KEY, p);
      },
    }),
    [palette],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useChartPalette(): ChartPaletteCtx {
  return useContext(Ctx);
}
