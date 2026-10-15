// SPDX-License-Identifier: Apache-2.0
import { useEffect } from "react";
import type { ReactNode } from "react";

/** Dark only: the light theme is parked, not half-shipped. */
const STORAGE_KEY = "tsy-theme";
const DARK_BG = "#121212"; // = --color-bg (tokens.css)

export function ThemeProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    const d = document.documentElement;
    d.removeAttribute("data-theme");
    d.style.colorScheme = "dark";
    d.style.background = DARK_BG;
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
    }
  }, []);

  return <>{children}</>;
}
