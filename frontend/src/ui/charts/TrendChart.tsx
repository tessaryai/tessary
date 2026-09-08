// SPDX-License-Identifier: Apache-2.0
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { chartTheme } from "./chartTheme";
import { trendYAxisWidth } from "./trendAxis";
import type { TrendYDomain } from "./trendAxis";

export interface TrendDatum {
  /** X-axis tick label (e.g. a short date). Not the series key. */
  label: string;
  value: number;
  /** Optional secondary line shown in the tooltip. */
  meta?: string;
  /**
   * Precise label for the tooltip (e.g. a full timestamp). Lets points that
   * share a calendar-day x label stay distinguishable on hover; falls back to
   * {@link label} when absent.
   */
  tooltipLabel?: string;
}

/**
 * Dark-themed line chart for the larger trend sections (Results,
 * Runs header). Single series; renders one marker per data point.
 *
 * Points are keyed by index, not by label, so several runs on the same calendar
 * day stay distinct points — hovering one surfaces that run's own value rather
 * than collapsing them onto a shared tick.
 */
export function TrendChart({
  data,
  yDomain,
  yTickFormat = (v) => String(v),
  valueLabel = "value",
  height = 160,
  color = chartTheme.defaultSeries,
}: {
  data: TrendDatum[];
  yDomain?: TrendYDomain;
  yTickFormat?: (v: number) => string;
  valueLabel?: string;
  height?: number;
  color?: string;
}) {
  if (data.length === 0) return null;
  const rows = data.map((d, i) => ({ ...d, idx: i }));
  // Size the y-axis to the widest tick that can actually render — the explicit
  // bounds when given, the data extent when the domain is left to recharts — so
  // labels like "100%" or "1120ms" aren't clipped. See ./trendAxis.
  const yAxisWidth = trendYAxisWidth(rows, yDomain, yTickFormat);
  return (
    <div style={{ width: "100%", height }}>
      <ResponsiveContainer>
        <LineChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
          <CartesianGrid {...chartTheme.cartesianGrid} />
          <XAxis
            dataKey="idx"
            type="number"
            domain={["dataMin", "dataMax"]}
            tickFormatter={(i: number) => rows[i]?.label ?? ""}
            tick={chartTheme.tick}
            axisLine={{ stroke: chartTheme.grid }}
            tickLine={false}
            minTickGap={24}
            allowDecimals={false}
          />
          <YAxis
            domain={yDomain ?? ["auto", "auto"]}
            tick={chartTheme.tick}
            axisLine={false}
            tickLine={false}
            width={yAxisWidth}
            tickFormatter={yTickFormat}
          />
          <Tooltip
            cursor={{ stroke: "var(--color-border-strong)" }}
            content={<TrendTooltip valueLabel={valueLabel} format={yTickFormat} />}
          />
          <Line
            type="linear"
            dataKey="value"
            stroke={color}
            strokeWidth={1.75}
            dot={{ r: 2, fill: color, strokeWidth: 0 }}
            activeDot={{ r: 3 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function TrendTooltip({
  active,
  payload,
  valueLabel,
  format,
}: {
  active?: boolean;
  label?: string | number;
  payload?: { payload: TrendDatum }[];
  valueLabel: string;
  format: (v: number) => string;
}) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <div
      className="rounded-card border px-2.5 py-1.5 text-small"
      style={chartTheme.tooltipSurface}
    >
      <div className="text-muted">{d.tooltipLabel ?? d.label}</div>
      <div className="text-fg tabular-nums">
        {valueLabel}: {format(d.value)}
      </div>
      {d.meta && <div className="text-muted">{d.meta}</div>}
    </div>
  );
}
