// SPDX-License-Identifier: Apache-2.0
import { createContext, useContext, useEffect, useMemo } from "react";
import type { ReactNode } from "react";

/**
 * Theming — DARK ONLY (2026-07 redesign; reaffirms the May 2026 decision).
 *
 * The light theme is parked entirely, not half-shipped: both toggles (sidebar,
 * Settings → Appearance) are removed and this provider hard-sets dark. The API
 * surface (`useTheme` → `{ theme, setTheme, toggle }`) is kept so existing
 * imports compile, but `theme` is always `"dark"` and the setters are no-ops.
 * If light ever earns a full pass, restore state here and re-grow the toggles.
 */
export type Theme = "light" | "dark";

const STORAGE_KEY = "tsy-theme";
const DARK_BG = "#121212"; // = --color-bg (tokens.css)

type ThemeCtx = {
  /** Always "dark". */
  theme: Theme;
  /** No-op — dark only. */
  setTheme: (t: Theme) => void;
  /** No-op — dark only. */
  toggle: () => void;
};

const Ctx = createContext<ThemeCtx | null>(null);

/** Hard-sets dark on <html> and clears any persisted light choice. */
export function ThemeProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    const d = document.documentElement;
    d.removeAttribute("data-theme");
    d.style.colorScheme = "dark";
    d.style.background = DARK_BG;
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // ignore persistence failures (private mode, disabled storage)
    }
  }, []);

  const value = useMemo<ThemeCtx>(
    () => ({ theme: "dark", setTheme: () => {}, toggle: () => {} }),
    [],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** Read the (fixed) theme. Throws if used outside a {@link ThemeProvider}. */
export function useTheme(): ThemeCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useTheme must be used within a ThemeProvider");
  return ctx;
}
