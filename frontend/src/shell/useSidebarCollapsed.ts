// SPDX-License-Identifier: Apache-2.0
/*
 * Sidebar collapse state — persisted per-browser (not per-project) so it
 * survives navigation and reload. `--sidebar-width-collapsed` (index.css)
 * already existed as a token before this hook consumed it.
 */
import { useCallback, useState } from "react";

const KEY = "tsy-sidebar-collapsed";

export function useSidebarCollapsed() {
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(KEY) === "1";
    } catch {
      return false;
    }
  });

  const toggle = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev;
      try {
        localStorage.setItem(KEY, next ? "1" : "0");
      } catch {
        // Storage unavailable (private mode, quota) — state just won't persist.
      }
      return next;
    });
  }, []);

  return { collapsed, toggle };
}
