// SPDX-License-Identifier: Apache-2.0
import { useEffect } from "react";
import type { ReactNode } from "react";

/**
 * Theming — DARK ONLY (2026-07 redesign; reaffirms the May 2026 decision).
 *
 * The light theme is parked entirely, not half-shipped: both toggles (sidebar,
 * Settings → Appearance) are removed and this provider hard-sets dark. If light
 * ever earns a full pass, add state and a context here and re-grow the toggles.
 */
const STORAGE_KEY = "tsy-theme";
const DARK_BG = "#121212"; // = --color-bg (tokens.css)

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

  return <>{children}</>;
}
