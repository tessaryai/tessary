// SPDX-License-Identifier: Apache-2.0
// One Escape must dismiss only the topmost overlay; a rail's onClose may navigate. stopPropagation
// cannot arbitrate between window listeners, so overlays join a stack and only the last-opened one
// acts and calls preventDefault.
import { useEffect, useRef } from "react";

const stack: symbol[] = [];

export function useEscToClose(active: boolean, onClose: () => void): void {
  const id = useRef<symbol | null>(null);
  if (id.current === null) id.current = Symbol("overlay");
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
