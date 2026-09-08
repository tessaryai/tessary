// SPDX-License-Identifier: Apache-2.0
import { chartTheme } from "./chartTheme";

/** One day's bucket: the count drives the bar height; `pct` is null when the day had no traces. */
export interface VolumeBucket {
  /** Short human label for the day, e.g. "Jul 15". */
  label: string;
  count: number;
  /** Fraction of that day's traces (0–1), or null when the denominator is 0. */
  pct: number | null;
  /** Full tooltip line, e.g. "Jul 15 · 123 traces · 4.2% of 2,940". */
  title: string;
}

/**
 * A compact per-row volume strip — one bar per bucket (typically 7 days), hand-rolled SVG in the
 * same idiom as Overview's ScoreStrip. Bar height encodes the count relative to the strip's max;
 * a zero-count day renders as a baseline tick (same "no data" grammar as ScoreStrip). Native
 * `<title>` tooltips carry the count and the %-of-traces readout per bar.
 */
export function VolumeBars({ buckets, ariaLabel }: { buckets: VolumeBucket[]; ariaLabel: string }) {
  const BAR_W = 10;
  const GAP = 4;
  const MAX_H = 20;
  const H = 24;
  const BASELINE = 22;
  const max = Math.max(1, ...buckets.map((b) => b.count));
  const width = buckets.length * BAR_W + (buckets.length - 1) * GAP;

  return (
    <svg
      width={width}
      height={H}
      viewBox={`0 0 ${width} ${H}`}
      role="img"
      aria-label={ariaLabel}
      className="shrink-0">
      {buckets.map((b, i) => {
        const x = i * (BAR_W + GAP);
        if (b.count === 0) {
          return (
            <rect key={i} x={x} y={BASELINE - 2} width={BAR_W} height={2} rx={1} fill="var(--color-border)">
              <title>{b.title}</title>
            </rect>
          );
        }
        const h = Math.max(3, Math.round((b.count / max) * MAX_H));
        return (
          <rect key={i} x={x} y={BASELINE - h} width={BAR_W} height={h} rx={1.5} fill={chartTheme.defaultSeries}>
            <title>{b.title}</title>
          </rect>
        );
      })}
    </svg>
  );
}
