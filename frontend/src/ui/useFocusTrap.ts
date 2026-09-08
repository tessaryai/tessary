// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef } from "react";

/**
 * Accessibility helper for hand-rolled overlays.
 *
 * The platform's native `<dialog showModal()>` primitives (Modal, Drawer,
 * CommandPalette) get focus-trapping, focus restoration and the inert backdrop
 * for free. Overlays built from plain elements — like the agent drawer, which
 * cannot use `<dialog>` because it streams and re-renders heavily — need the
 * same WCAG behaviour wired by hand:
 *
 *  - move focus into the overlay on open (the element this returns a ref for, or
 *    its first focusable child),
 *  - keep Tab / Shift+Tab cycling within the overlay,
 *  - restore focus to the element that was focused before opening, on close.
 *
 * Pass `active` so the trap only runs while the overlay is mounted/visible.
 * Attach the returned ref to the overlay's container element.
 */
export function useFocusTrap<T extends HTMLElement>(active: boolean) {
  const containerRef = useRef<T>(null);

  useEffect(() => {
    if (!active) return;
    const container = containerRef.current;
    if (!container) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;

    const focusables = () =>
      Array.from(
        container.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      ).filter((el) => el.offsetParent !== null || el === document.activeElement);

    // Move focus into the overlay (prefer an explicitly [autofocus]-flagged
    // element, else the first focusable, else the container itself).
    const initial =
      container.querySelector<HTMLElement>("[data-autofocus]") ?? focusables()[0] ?? container;
    initial.focus();

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Tab") return;
      const items = focusables();
      if (items.length === 0) {
        e.preventDefault();
        container.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const activeEl = document.activeElement;
      if (e.shiftKey) {
        if (activeEl === first || !container.contains(activeEl)) {
          e.preventDefault();
          last.focus();
        }
      } else if (activeEl === last) {
        e.preventDefault();
        first.focus();
      }
    };

    container.addEventListener("keydown", onKeyDown);
    return () => {
      container.removeEventListener("keydown", onKeyDown);
      // Restore focus to where it was when the overlay opened.
      previouslyFocused?.focus?.();
    };
  }, [active]);

  return containerRef;
}
