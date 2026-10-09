// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo, useState } from "react";
import type { ReactNode } from "react";

/**
 * Display density for data-heavy surfaces (traces, results tables).
 *
 * "comfortable" is the default rhythm used everywhere; "compact" tightens row
 * padding and line-height so more rows fit on screen. The mechanism is purely
 * token-driven: a `data-density` attribute on a wrapper element re-points the
 * `--density-*` CSS vars (see index.css), and density-aware components read
 * those vars. Nothing re-renders on toggle beyond the attribute flip.
 */
export type Density = "comfortable" | "compact";

type DensityCtx = {
  density: Density;
  setDensity: (d: Density) => void;
};

const Ctx = createContext<DensityCtx>({
  density: "comfortable",
  setDensity: () => {},
});

/**
 * Provides density state and stamps `data-density` onto a wrapping element so
 * the CSS vars cascade to every descendant. Wrap a data-heavy view (or a single
 * table) in this; read the current value with {@link useDensity}. The choice is
 * read from and written to localStorage so it applies app-wide and survives
 * reloads; with nothing stored it starts at "comfortable".
 */
/** Account-level density preference. Stubbed in localStorage until a
 *  /me/preferences API lands. */
const STORAGE_KEY = "tessary.prefs.density";

function readStoredDensity(): Density {
  const v = window.localStorage.getItem(STORAGE_KEY);
  return v === "compact" || v === "comfortable" ? v : "comfortable";
}

export function DensityProvider({ className, children }: { className?: string; children: ReactNode }) {
  const [density, setDensityState] = useState<Density>(readStoredDensity);
  const setDensity = (d: Density) => {
    setDensityState(d);
    window.localStorage.setItem(STORAGE_KEY, d);
  };
  const value = useMemo<DensityCtx>(
    () => ({ density, setDensity }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [density],
  );
  return (
    <Ctx.Provider value={value}>
      <div data-density={density} className={className}>
        {children}
      </div>
    </Ctx.Provider>
  );
}

/** Read/control the nearest density scope. Defaults to comfortable outside a provider. */
export function useDensity(): DensityCtx {
  return useContext(Ctx);
}
