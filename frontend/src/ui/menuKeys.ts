// SPDX-License-Identifier: Apache-2.0
import type { FocusEvent, RefObject } from "react";

export function itemsOf(panel: HTMLElement | null, role: string): HTMLElement[] {
  return panel ? Array.from(panel.querySelectorAll<HTMLElement>(`[role="${role}"]`)) : [];
}

/** Focus that goes nowhere is left to outside-click handling, so a click on a non-focusable option lands. */
export function closeOnFocusOut(container: RefObject<HTMLElement | null>, close: () => void) {
  return (e: FocusEvent) => {
    const next = e.relatedTarget as Node | null;
    if (next && container.current && !container.current.contains(next)) close();
  };
}
