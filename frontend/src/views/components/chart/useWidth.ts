// SPDX-License-Identifier: Apache-2.0
import { useLayoutEffect, useRef, useState } from "react";
import type { RefObject } from "react";

export const FALLBACK_W = 520;

/** The measured width of a box, and FALLBACK_W until it reports a positive clientWidth (jsdom never does). */
export function useWidth(): readonly [RefObject<HTMLDivElement | null>, number] {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(FALLBACK_W);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const read = () => {
      if (el.clientWidth > 0) setWidth(el.clientWidth);
    };
    read();
    const observer = new ResizeObserver(read);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}
