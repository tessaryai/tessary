// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

/**
 * A small determinate progress bar. The track is `bg-border` (grey-700), which is the ramp step
 * the palette assigns to progress tracks — not a surface level, so the bar reads the same on a
 * card as it does on the canvas.
 *
 * Pass either `percent` (0..100) directly, or `value`/`max` to derive it.
 * `barClassName` lets callers swap the fill color (e.g. `bg-warning` vs `bg-accent`).
 */
export function ProgressBar({
  percent,
  value,
  max,
  className,
  barClassName = "bg-accent",
}: {
  percent?: number;
  value?: number;
  max?: number;
  className?: string;
  barClassName?: string;
}) {
  const pct =
    percent != null
      ? percent
      : max != null && max > 0
        ? ((value ?? 0) / max) * 100
        : 0;
  const clamped = Math.max(0, Math.min(100, Math.round(pct)));
  return (
    <div
      className={cn("h-1.5 rounded-pill bg-border overflow-hidden", className)}
      role="progressbar"
      aria-valuenow={clamped}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div
        className={cn("h-full transition-[width]", barClassName)}
        style={{ width: `${clamped}%`, transitionDuration: "var(--duration-micro)" }}
      />
    </div>
  );
}
