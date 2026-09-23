// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { trendYAxisWidth } from "./trendAxis";

const series = (...values: number[]) => values.map((value, i) => ({ label: `d${i}`, value }));

const ms = (v: number) => `${v}ms`;

describe("trendYAxisWidth", () => {
  it("sizes an auto-domain axis to the data, not to the 40px floor", () => {
    // The regression: with no yDomain the width used to collapse to 40px and clip
    // the leading glyph of "1120ms", rendering it as "l40ms".
    const width = trendYAxisWidth(series(812, 845, 790, 1120, 860, 831, 902), undefined, ms);
    expect(width).toBeGreaterThan(40);
    expect(width).toBeGreaterThanOrEqual(ms(1120).length * 7);
  });

  // Width = widest tick's glyphs * 7px + 12px gutter, floored at 40px. Expected values below are
  // hand-computed from that formula, and each is above the floor so the floor cannot satisfy them.

  it("covers the nice tick just past the data extent", () => {
    // Bug: the gutter sized to 990 ("990ms", 47px) clips the leading glyph of the 1000 tick that
    // recharts' outward-rounded auto domain renders. "1000ms" is 6 glyphs: 6 * 7 + 12 = 54.
    expect(trendYAxisWidth(series(120, 990), undefined, ms)).toBe(54);
  });

  it("sizes off explicit numeric bounds, not the data", () => {
    // Bug: ignoring the bounds sizes to the 96.1 point ("96.1ms", 54px) and clips the 10000 tick.
    // "10000ms" is 7 glyphs: 7 * 7 + 12 = 61.
    expect(trendYAxisWidth(series(96.1), [0, 10000], ms)).toBe(61);
    // Bug: measuring the data under an explicit domain widens the gutter for a point recharts never
    // ticks. "100ms" is 5 glyphs: 5 * 7 + 12 = 47, not "96.125ms" at 68.
    expect(trendYAxisWidth(series(96.125), [0, 100], ms)).toBe(47);
  });

  it("falls back to the data for a keyword bound", () => {
    const keyword = trendYAxisWidth(series(790, 1120), ["dataMin", "dataMax"], ms);
    expect(keyword).toBe(trendYAxisWidth(series(790, 1120), undefined, ms));
  });

  it("measures every point, not only the extent", () => {
    // A formatter need not be monotonic in width: -5 is wider than 10.
    const width = trendYAxisWidth(series(-5, 10), undefined, String);
    expect(width).toBeGreaterThanOrEqual(String(-10).length * 7);
  });

  it("never goes below the 40px floor", () => {
    expect(trendYAxisWidth(series(1, 2, 3), undefined, String)).toBe(40);
    expect(trendYAxisWidth([], undefined, String)).toBe(40);
  });
});
