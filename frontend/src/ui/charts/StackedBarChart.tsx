// SPDX-License-Identifier: Apache-2.0
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { chartTheme } from "./chartTheme";

/** One stack segment across the whole chart — a category, in draw order (bottom of the stack first). */
export interface BarSeries {
  key: string;
  label: string;
  color: string;
}

/** One bar: an x position plus this bucket's value for each series (missing keys count as 0). */
export interface BarDatum {
  /** X-axis tick label — kept short, since ticks thin out but don't wrap. */
  label: string;
  /** Full label for the tooltip (e.g. an exact timestamp); falls back to {@link label}. */
  tooltipLabel?: string;
  values: Record<string, number>;
}

/**
 * A stacked bar chart in the shared chart language — the shape for "this measure over time, split by
 * one categorical axis". Series colors are the caller's (walk `seriesColor(i)` so a legend rendered
 * elsewhere can match); the axes, grid and tooltip surface come from `chartTheme`.
 *
 * Bars are keyed by index rather than by label so two buckets that format to the same tick (a day
 * repeated across months, an hour repeated across days) stay separate bars instead of merging.
 */
export function StackedBarChart({
  data,
  series,
  valueFormat = (v) => String(v),
  tooltipFormat = valueFormat,
  valueLabel = "Total",
  height = 260,
}: {
  data: BarDatum[];
  series: BarSeries[];
  /** Axis-tick formatter — has room for a magnitude, not for full precision. */
  valueFormat?: (v: number) => string;
  /** Tooltip formatter; defaults to {@link valueFormat}. Pass a precise one where the axis rounds. */
  tooltipFormat?: (v: number) => string;
  /** Name for the summed row in the tooltip — "Tokens", "Cost", "Calls". */
  valueLabel?: string;
  height?: number;
}) {
  const rows = data.map((d, i) => {
    const flat: Record<string, string | number> = { idx: i, label: d.label, tooltipLabel: d.tooltipLabel ?? d.label };
    for (const s of series) flat[s.key] = d.values[s.key] ?? 0;
    return flat;
  });

  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer>
        <BarChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }} barCategoryGap="12%">
          <CartesianGrid {...chartTheme.cartesianGrid} />
          <XAxis
            dataKey="idx"
            type="category"
            tickFormatter={(i: number) => data[i]?.label ?? ""}
            tick={chartTheme.tick}
            axisLine={{ stroke: chartTheme.grid }}
            tickLine={false}
            minTickGap={20}
            interval="preserveStartEnd"
          />
          {/* Decimals stay allowed: a cost axis topping out at $0.004 would otherwise tick 0 and 1
              and flatten every bar to nothing. `valueFormat` is what keeps the ticks readable. */}
          <YAxis
            tick={chartTheme.tick}
            axisLine={false}
            tickLine={false}
            width={56}
            tickFormatter={valueFormat}
          />
          <Tooltip
            cursor={{ fill: "var(--color-hover)" }}
            content={<StackTooltip series={series} format={tooltipFormat} valueLabel={valueLabel} />}
          />
          {series.map((s) => (
            <Bar key={s.key} dataKey={s.key} stackId="usage" fill={s.color} isAnimationActive={false} />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * Segments are listed largest-first and zero segments dropped — a stack of twenty models is unreadable
 * in draw order, and the reader is looking for which one dominates the bucket.
 */
function StackTooltip({
  active,
  payload,
  series,
  format,
  valueLabel,
}: {
  active?: boolean;
  payload?: { payload: Record<string, string | number> }[];
  series: BarSeries[];
  format: (v: number) => string;
  valueLabel: string;
}) {
  if (!active || !payload?.length) return null;
  const row = payload[0].payload;
  const parts = series
    .map((s) => ({ ...s, value: Number(row[s.key] ?? 0) }))
    .filter((p) => p.value > 0)
    .sort((a, b) => b.value - a.value);
  const total = parts.reduce((sum, p) => sum + p.value, 0);

  return (
    <div className="rounded-card border px-2.5 py-2 text-small" style={chartTheme.tooltipSurface}>
      <div className="text-muted">{String(row.tooltipLabel ?? row.label)}</div>
      <div className="mt-1 text-fg tabular-nums">
        {valueLabel}: {format(total)}
      </div>
      {parts.length > 1 && (
        <div className="mt-1.5 flex flex-col gap-0.5">
          {parts.map((p) => (
            <div key={p.key} className="flex items-center gap-1.5 text-muted">
              <span className="inline-block h-2 w-2 shrink-0 rounded-pill" style={{ background: p.color }} />
              <span className="min-w-0 flex-1 truncate">{p.label}</span>
              <span className="font-mono tabular-nums text-fg">{format(p.value)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
