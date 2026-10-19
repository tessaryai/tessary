// SPDX-License-Identifier: Apache-2.0
import { useCallback, useState } from "react";

/**
 * The measured height of a box that may mount and unmount, so the ref is a callback whose cleanup React 19 runs on
 * unmount instead of calling it with null: 0 while the box is absent or before it reports a height (jsdom never does).
 */
export function useHeight<T extends HTMLElement>(): readonly [(el: T) => () => void, number] {
  const [height, setHeight] = useState(0);
  const ref = useCallback((el: T) => {
    const read = () => setHeight(el.clientHeight);
    read();
    const observer = new ResizeObserver(read);
    observer.observe(el);
    return () => {
      observer.disconnect();
      setHeight(0);
    };
  }, []);
  return [ref, height] as const;
}
