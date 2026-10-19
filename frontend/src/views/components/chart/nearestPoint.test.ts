// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { nearestPoint } from "./nearestPoint";

const spans = [
  { a: 0, b: 10 },
  { a: 20, b: 30 },
  { a: 40, b: 50 },
];

// Each case catches a hover that lands on the wrong bucket.
describe("nearestPoint", () => {
  it("picksTheSpanThatHoldsThePixel", () => {
    expect(nearestPoint(spans, 25)).toBe(1);
    expect(nearestPoint(spans, 40)).toBe(2);
  });

  it("picksTheNearestEdgeOutsideEverySpan", () => {
    expect(nearestPoint(spans, 14)).toBe(0);
    expect(nearestPoint(spans, 15)).toBe(0);
    expect(nearestPoint(spans, 17)).toBe(1);
    expect(nearestPoint(spans, 99)).toBe(2);
    expect(nearestPoint(spans, -5)).toBe(0);
  });

  it("returnsNullForNoSpans", () => {
    expect(nearestPoint([], 12)).toBeNull();
  });
});
