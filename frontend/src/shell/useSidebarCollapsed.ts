// SPDX-License-Identifier: Apache-2.0
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
      }
      return next;
    });
  }, []);

  return { collapsed, toggle };
}
