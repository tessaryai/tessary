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
  toggle: () => void;
};

const Ctx = createContext<DensityCtx>({
  density: "comfortable",
  setDensity: () => {},
  toggle: () => {},
});

/**
 * Provides density state and stamps `data-density` onto a wrapping element so
 * the CSS vars cascade to every descendant. Wrap a data-heavy view (or a single
 * table) in this; read the current value with {@link useDensity}.
 *
 * `as` lets the wrapper be something other than a div (e.g. a fragment is not
 * possible since the attribute must live on a real element). `defaultValue`
 * seeds uncontrolled state.
 */
/** Account-level density preference. Stubbed in localStorage until a
 *  /me/preferences API lands (see Settings → Appearance). */
const STORAGE_KEY = "tessary.prefs.density";

function readStoredDensity(fallback: Density): Density {
  if (typeof window === "undefined") return fallback;
  const v = window.localStorage.getItem(STORAGE_KEY);
  return v === "compact" || v === "comfortable" ? v : fallback;
}

export function DensityProvider({
  defaultValue = "comfortable",
  persist = false,
  className,
  children,
}: {
  defaultValue?: Density;
  /** When set, the choice is read from and written to localStorage so it
   *  applies app-wide and survives reloads (used by the root provider). */
  persist?: boolean;
  className?: string;
  children: ReactNode;
}) {
  const [density, setDensityState] = useState<Density>(() =>
    persist ? readStoredDensity(defaultValue) : defaultValue,
  );
  const setDensity = (d: Density) => {
    setDensityState(d);
    if (persist && typeof window !== "undefined") window.localStorage.setItem(STORAGE_KEY, d);
  };
  const value = useMemo<DensityCtx>(
    () => ({
      density,
      setDensity,
      toggle: () => setDensity(density === "compact" ? "comfortable" : "compact"),
    }),
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
