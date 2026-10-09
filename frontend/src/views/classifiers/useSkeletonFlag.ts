// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState } from "react";

/** How long a filter switch waits before swapping the last list for the skeleton, in ms. */
const SKELETON_DELAY = 200;

/** The shortest time the skeleton stays once drawn, in ms. */
const SKELETON_HOLD = 400;

/**
 * Whether a list that is switching filters should draw its skeleton: only once the switch has been
 * loading for {@link SKELETON_DELAY}, and then for at least {@link SKELETON_HOLD}. A quick read keeps the
 * last list up rather than flashing a skeleton, and a slow one does not flicker back the moment it lands.
 */
export function useSkeletonFlag(active: boolean): boolean {
  const [shown, setShown] = useState(false);
  const shownAt = useRef(0);
  useEffect(() => {
    if (active === shown) return;
    const wait = active ? SKELETON_DELAY : Math.max(0, SKELETON_HOLD - (Date.now() - shownAt.current));
    const timer = setTimeout(() => {
      shownAt.current = Date.now();
      setShown(active);
    }, wait);
    return () => clearTimeout(timer);
  }, [active, shown]);
  return shown;
}
