// SPDX-License-Identifier: Apache-2.0
/**
 * The shared data-viz language for ui/charts/.
 *
 * Every chart — recharts-based (TrendChart) or hand-rolled SVG (Sparkline) —
 * pulls its colors, axis chrome, and tooltip styling from here so dashboards
 * and breakdowns read as one system. Values are CSS-var references, so charts
 * stay token-driven and inherit any future theme changes for free.
 *
 * Rules of the language:
 *  - Series colors come from the "Siblings" ramp and nowhere else. It is eight
 *    hues assigned 1→8 left to right in a FIXED order and NEVER cycled — a
 *    ninth series folds into a neutral "Other" rather than repeating hue 1,
 *    because two series sharing a color is worse than one series losing its own.
 *  - The ramp deliberately contains no status hue. A green line would read as
 *    "passing" whatever the series actually is, so red/green/blue never appear
 *    here; that separation is what lets a hue mean something elsewhere.
 *  - Single-series charts use `chartTheme.defaultSeries` (= series 1, lavender).
 *    NOT the UI accent — the UI accent is grey-50 and belongs to controls.
 *  - Axis ticks use --color-chart-axis (muted); gridlines use --color-chart-grid
 *    (border) and are horizontal-only by default. Chart text is always the grey
 *    ramp, never a series color.
 *  - Status-bearing series (pass/fail/warn) use chartStatusColors below instead
 *    of the categorical ramp, so green==good stays true.
 */

/** Distinct categorical hues in the ramp. A series past this folds into "Other". */
export const CHART_SERIES_COUNT = 8;

/** The neutral a 9th-and-beyond series collapses to. Grey, so it never reads as a verdict. */
export const CHART_SERIES_OTHER = "var(--color-muted)";

/**
 * Color for the i-th series (0-based). Does NOT wrap: index 8 and beyond return
 * {@link CHART_SERIES_OTHER}. Callers rendering more than eight series should group the tail
 * into a single "Other" row so the legend matches what is drawn.
 */
export function seriesColor(i: number): string {
  if (i < 0 || i >= CHART_SERIES_COUNT) return CHART_SERIES_OTHER;
  return `var(--color-chart-series-${i + 1})`;
}

/** Full categorical ramp, in order — handy for legends or `<Cell>` mapping. */
export const chartSeriesColors: string[] = Array.from({ length: CHART_SERIES_COUNT }, (_, i) =>
  seriesColor(i),
);

/** Semantic colors for status-bearing series (use instead of the ramp when a hue carries meaning). */
export const chartStatusColors = {
  success: "var(--color-success)",
  warning: "var(--color-warning)",
  error: "var(--color-error)",
  neutral: "var(--color-muted)",
} as const;

/** Shared chart chrome — spread onto recharts axis/grid/tooltip elements. */
export const chartTheme = {
  /** Default single-series line/area/bar color: series 1 of the ramp, NOT the grey UI accent. */
  defaultSeries: seriesColor(0),
  grid: "var(--color-chart-grid)",
  axis: "var(--color-chart-axis)",
  /** Pixel font size for axis ticks; matches the system's --text-label rhythm. */
  tickFontSize: 11,
  /** Props for a recharts <CartesianGrid> in our language (faint, horizontal). */
  cartesianGrid: { stroke: "var(--color-chart-grid)", vertical: false } as const,
  /** Tick style object for recharts axes. */
  tick: { fill: "var(--color-chart-axis)", fontSize: 11 } as const,
  /** Tooltip container style — matches the overlay surface used elsewhere. */
  tooltipSurface: {
    background: "var(--color-overlay)",
    borderColor: "var(--color-border-strong)",
  } as const,
} as const;
