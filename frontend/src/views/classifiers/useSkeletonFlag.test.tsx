// SPDX-License-Identifier: Apache-2.0
/*
 * When a list switching filters shows its skeleton. The bugs worth catching: a skeleton that flashes
 * for a read that came back quickly, and one that vanishes the instant a slow read lands, so the list
 * flickers between the two.
 */
import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSkeletonFlag } from "./useSkeletonFlag";

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

const advance = (ms: number) => act(() => vi.advanceTimersByTime(ms));

describe("useSkeletonFlag", () => {
  it("never shows a skeleton for a read that settles inside the delay", () => {
    const { result, rerender } = renderHook(({ loading }) => useSkeletonFlag(loading), {
      initialProps: { loading: true },
    });

    advance(150);
    rerender({ loading: false });
    advance(1000);

    expect(result.current).toBe(false);
  });

  it("shows it once the read outlasts the delay, and holds it for the minimum even if the read lands", () => {
    const { result, rerender } = renderHook(({ loading }) => useSkeletonFlag(loading), {
      initialProps: { loading: true },
    });

    advance(199);
    expect(result.current).toBe(false);
    advance(1);
    expect(result.current).toBe(true);

    advance(100);
    rerender({ loading: false });
    advance(299);
    expect(result.current).toBe(true);
    advance(1);
    expect(result.current).toBe(false);
  });

  it("drops it at once when the read lands after the minimum has passed", () => {
    const { result, rerender } = renderHook(({ loading }) => useSkeletonFlag(loading), {
      initialProps: { loading: true },
    });
    advance(200);
    advance(1000);

    rerender({ loading: false });
    advance(0);

    expect(result.current).toBe(false);
  });
});
