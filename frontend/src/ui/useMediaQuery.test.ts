// SPDX-License-Identifier: Apache-2.0
import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { LG_QUERY, useMediaQuery } from "./useMediaQuery";

type Listener = (e: { matches: boolean }) => void;

function stubMatchMedia(matches: boolean) {
  const listeners: Listener[] = [];
  const mq = {
    matches,
    addEventListener: vi.fn((_: string, l: Listener) => listeners.push(l)),
    removeEventListener: vi.fn(),
  };
  const matchMedia = vi.fn(() => mq);
  vi.stubGlobal("matchMedia", matchMedia);
  return { mq, listeners, matchMedia };
}

describe("useMediaQuery", () => {
  // Catches the smoke test and every page test, where jsdom has no matchMedia, rendering the stacked layout.
  it("isTrueWithoutMatchMedia", () => {
    vi.stubGlobal("matchMedia", undefined);

    const { result } = renderHook(() => useMediaQuery(LG_QUERY));

    expect(result.current).toBe(true);
  });

  // Catches a grid that keeps its first layout after the window crosses the breakpoint.
  it("followsMatchesAndTheChangeEvent", () => {
    const { listeners, matchMedia } = stubMatchMedia(false);

    const { result } = renderHook(() => useMediaQuery(LG_QUERY));
    expect(matchMedia).toHaveBeenCalledWith("(min-width: 1024px)");
    expect(result.current).toBe(false);

    act(() => listeners.forEach((l) => l({ matches: true })));
    expect(result.current).toBe(true);
  });

  // Catches a listener left on the media query after the page unmounts.
  it("unsubscribesOnUnmount", () => {
    const { mq, listeners } = stubMatchMedia(true);

    const { unmount } = renderHook(() => useMediaQuery(LG_QUERY));
    unmount();

    expect(mq.removeEventListener).toHaveBeenCalledWith("change", listeners[0]);
  });
});
