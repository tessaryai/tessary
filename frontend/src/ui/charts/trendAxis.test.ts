// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from "vitest";
import { trendYAxisWidth } from "./trendAxis";

const series = (...values: number[]) => values.map((value, i) => ({ label: `d${i}`, value }));

const ms = (v: number) => `${v}ms`;
const pct = (v: number) => `${v}%`;

describe("trendYAxisWidth", () => {
  it("sizes an auto-domain axis to the data, not to the 40px floor", () => {
    // The regression: with no yDomain the width used to collapse to 40px and clip
    // the leading glyph of "1120ms", rendering it as "l40ms".
    const width = trendYAxisWidth(series(812, 845, 790, 1120, 860, 831, 902), undefined, ms);
    expect(width).toBeGreaterThan(40);
    expect(width).toBeGreaterThanOrEqual(ms(1120).length * 7);
  });

  it("covers the nice tick just past the data extent", () => {
    // recharts' auto domain rounds outward, so a 990 max can render a 1000 tick.
    expect(trendYAxisWidth(series(120, 990), undefined, ms)).toBeGreaterThanOrEqual(
      ms(1000).length * 7,
    );
  });

  it("keeps sizing off explicit numeric bounds alone", () => {
    // Unchanged behaviour for the callers that pass a domain: [80,100] with "%".
    // The 96.1 point is deliberately NOT measured — recharts scales strictly to
    // the bounds, so it never becomes a tick and must not widen the gutter.
    expect(trendYAxisWidth(series(96.1, 89.7), [80, 100], pct)).toBe(
      Math.max(40, pct(100).length * 7 + 12),
    );
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
