// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";

/** Tailwind's lg, the breakpoint the sidebar token also narrows at. */
export const LG_QUERY = "(min-width: 1024px)";

function mediaQuery(query: string): MediaQueryList | null {
  return typeof window.matchMedia === "function" ? window.matchMedia(query) : null;
}

/** True while `query` matches; true when the browser has no matchMedia (jsdom), so the desktop layout is the test default. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => mediaQuery(query)?.matches ?? true);
  useEffect(() => {
    const mq = mediaQuery(query);
    if (!mq) return;
    setMatches(mq.matches);
    const onChange = (e: { matches: boolean }) => setMatches(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [query]);
  return matches;
}
