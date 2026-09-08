// SPDX-License-Identifier: Apache-2.0

/** A recharts y-axis domain: either an explicit number or one of its keywords. */
export type TrendYBound = number | "auto" | "dataMin" | "dataMax";
export type TrendYDomain = [TrendYBound, TrendYBound];

/** Narrowest y-axis gutter, in px. Matches the pre-existing floor. */
const MIN_Y_AXIS_WIDTH = 40;
/** Approximate advance width of a tick glyph at the 11px chart font, in px. */
const GLYPH_PX = 7;
/** Breathing room between the widest tick and the plot area, in px. */
const GUTTER_PX = 12;
/** Never size for fewer glyphs than this, so short-tick charts keep a stable gutter. */
const MIN_GLYPHS = 3;

/**
 * Round `v` outward (away from the data) to the next multiple of its leading
 * power of ten.
 *
 * A `"auto"`/`"dataMin"`/`"dataMax"` bound does NOT make recharts stop at the
 * data extent — it picks nice tick boundaries just past it, so a series topping
 * out at 990 can render a `1000` tick that is a glyph wider than anything in the
 * data. Widening the extent this way before formatting keeps that tick inside
 * the measured width.
 */
function roundOutward(v: number, direction: "up" | "down"): number {
  if (!Number.isFinite(v) || v === 0) return v;
  const step = 10 ** Math.floor(Math.log10(Math.abs(v)));
  return direction === "up" ? Math.ceil(v / step) * step : Math.floor(v / step) * step;
}

/**
 * Width of the y-axis gutter, sized to the widest tick the chart can actually
 * render rather than to the explicit bounds alone.
 *
 * The bounds are only half the story: when a caller omits `yDomain` (or passes a
 * keyword bound), recharts derives the scale from the data, so the data extent
 * is what the ticks come from. Sizing off the bounds alone left every
 * auto-domain chart on the {@link MIN_Y_AXIS_WIDTH} floor and clipped the
 * leading glyph of anything wider — `1120ms` rendering as `l40ms`.
 *
 * When the domain IS fully explicit the data is not measured at all: recharts
 * scales strictly to those bounds, so a `96.1` point under a `[80, 100]` domain
 * never becomes a tick. Otherwise every data value is measured, not just the
 * extent, because a formatter need not be monotonic in width (`-5` is wider than
 * `10`, `999` wider than `1.2k`).
 */
export function trendYAxisWidth(
  data: readonly { value: number }[],
  yDomain: TrendYDomain | undefined,
  yTickFormat: (v: number) => string,
): number {
  const bounds = [yDomain?.[0], yDomain?.[1]];
  const fromData = bounds.some((b) => typeof b !== "number");
  const values = data.map((d) => d.value).filter((v) => Number.isFinite(v));
  const candidates: number[] = fromData ? [...values] : [];
  const derived: [number | undefined, number | undefined] =
    fromData && values.length > 0
      ? [roundOutward(Math.min(...values), "down"), roundOutward(Math.max(...values), "up")]
      : [undefined, undefined];
  for (const [i, bound] of bounds.entries()) {
    if (typeof bound === "number") candidates.push(bound);
    else {
      const fallback = derived[i];
      if (fallback !== undefined) candidates.push(fallback);
    }
  }
  const widestTick = candidates
    .map((v) => yTickFormat(v).length)
    .reduce((m, len) => Math.max(m, len), MIN_GLYPHS);
  return Math.max(MIN_Y_AXIS_WIDTH, widestTick * GLYPH_PX + GUTTER_PX);
}
