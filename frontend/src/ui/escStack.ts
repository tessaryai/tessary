// SPDX-License-Identifier: Apache-2.0
/*
 * ESC-to-dismiss for STACKED overlays (rails, the Ask drawer). One keypress
 * must dismiss only the topmost overlay — never a rail underneath it, whose
 * onClose may navigate (TraceDetail's rail mode navigates back to its opener).
 *
 * `e.stopPropagation()` cannot arbitrate this: every window keydown listener
 * fires regardless. Instead each open overlay joins a module-level stack;
 * every listener still fires, but only the LAST-OPENED overlay acts, and it
 * consumes the event (preventDefault) so any other global ESC consumer that
 * checks `e.defaultPrevented` stays quiet. Overlays with element-level Escape
 * handling (the ⌘K palette's <dialog> keydown) sit earlier in the propagation
 * path, so their preventDefault reaches here too.
 */
import { useEffect, useRef } from "react";

const stack: symbol[] = [];

/** While `active`, close this overlay on Escape — but only when it is topmost. */
export function useEscToClose(active: boolean, onClose: () => void): void {
  const id = useRef<symbol | null>(null);
  if (id.current === null) id.current = Symbol("overlay");
  // Latest onClose without re-subscribing (and without re-pushing the stack).
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!active) return;
    const me = id.current!;
    stack.push(me);
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (e.defaultPrevented) return; // a higher overlay already took this ESC
      if (stack[stack.length - 1] !== me) return; // not the topmost overlay
      e.preventDefault(); // consume it for every other global ESC listener
      onCloseRef.current();
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      const at = stack.indexOf(me);
      if (at !== -1) stack.splice(at, 1);
    };
  }, [active]);
}
