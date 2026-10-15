// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useMemo, useState } from "react";
import type { ReactNode } from "react";

/** A `data-density` attribute re-points the `--density-*` CSS vars; nothing re-renders but the attribute. */
export type Density = "comfortable" | "compact";

type DensityCtx = {
  density: Density;
  setDensity: (d: Density) => void;
};

const Ctx = createContext<DensityCtx>({
  density: "comfortable",
  setDensity: () => {},
});

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

export function useDensity(): DensityCtx {
  return useContext(Ctx);
}
