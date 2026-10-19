// SPDX-License-Identifier: Apache-2.0
/*
 * One measure over the window, one series per key in the server's rank order with the fold last: step lines for
 * amounts, bars for counts. A missing bucket is a gap, never a zero. Coordinates are CSS pixels, so tick text stays the
 * small type size at every card width.
 */
import { useId, useState } from "react";
import { toAxis } from "../../classifiers/chartRules";
import { Legend } from "../../components/chart/Legend";
import { nearestPoint } from "../../components/chart/nearestPoint";
import { Tooltip } from "../../components/chart/Tooltip";
import { useHeight } from "../../components/chart/useHeight";
import { useWidth } from "../../components/chart/useWidth";
import {
  axisTicks, axisUnit, bucketLabel, formatValue, HOUR_MS, type Series, seriesColour, seriesDash, seriesPaths, placeTicks, timeTicks,
  type Window,
} from "../metricsRules";
import type { MetricsUnit } from "../wire";

const ML = 48;
const MR = 8;
const MT = 10;
/** The x-axis band under the plot: tick marks and their labels. */
const AXIS_BAND = 22;
/** The one legend line chartHeightFor budgets; each further line the legend wraps to comes out of the plot. */
const LEGEND_LINE = 18;
const BUCKET_MS = { hour: HOUR_MS, day: 24 * HOUR_MS, week: 7 * 24 * HOUR_MS };

type Granularity = "hour" | "day" | "week";

const rows = (n: number) => (n === 1 ? "1 row" : `${n.toLocaleString("en-US")} rows`);

export function TimeSeriesChart({
  title,
  unit,
  window,
  granularity,
  series,
  height: cardHeight,
  bars,
}: {
  title: string;
  unit: MetricsUnit;
  window: Window;
  granularity: Granularity | null;
  series: Series[];
  height: number;
  bars: boolean;
}) {
  const [hover, setHover] = useState<number | null>(null);
  // Only a keyboard reader hears the buckets: a mouse moving over the chart would otherwise talk over everything.
  const [keyboard, setKeyboard] = useState(false);
  const [boxRef, W] = useWidth();
  const [legendRef, legendHeight] = useHeight<HTMLUListElement>();
  const height = cardHeight - Math.max(0, legendHeight - LEGEND_LINE);
  const liveId = useId();

  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  // A total result charts one point at the window start, held over the whole window.
  const bucketMs = granularity ? BUCKET_MS[granularity] : to - from;
  const PB = height - AXIS_BAND;
  const au = axisUnit(unit);
  const x = (t: number) => ML + ((Math.max(from, Math.min(to, t)) - from) / (to - from)) * (W - ML - MR);

  const values = series.flatMap((s) => s.points.flatMap((p) => (p.v == null ? [] : [p.v])));
  const max = Math.max(0, ...values);
  const axis = axisTicks(unit, unit === "count" ? max : max * 1.08);
  const yAxisPx = (a: number) => MT + (1 - a / axis.top) * (PB - MT);
  const y = (v: number) => yAxisPx(toAxis(au, v));

  const buckets = series[0]?.points.map((p) => p.at) ?? [];
  const n = buckets.length;
  const spans = buckets.map((at) => ({ a: x(at), b: x(at + bucketMs) }));
  const ticks = placeTicks(timeTicks(window, granularity), x, ML - 6, W);

  const shown = hover != null && hover < n ? hover : null;
  const hoverX = shown == null ? null : (spans[shown].a + spans[shown].b) / 2;
  const lines = (i: number) => [
    bucketLabel(new Date(buckets[i]).toISOString(), granularity ?? "day"),
    ...series.map((s) => `${s.label}: ${formatValue(unit, s.points[i].v)}`),
    rows(series.reduce((sum, s) => sum + s.points[i].n, 0)),
  ];

  const move = (i: number) => setHover(Math.max(0, Math.min(n - 1, i)));
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (n === 0) return;
    if (e.key === "ArrowLeft") move((shown ?? n - 1) - 1);
    else if (e.key === "ArrowRight") move((shown ?? n - 1) + 1);
    else if (e.key === "Home") move(0);
    else if (e.key === "End") move(n - 1);
    else return;
    e.preventDefault();
  };
  const onMouseMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const box = e.currentTarget.getBoundingClientRect();
    setHover(nearestPoint(spans, ((e.clientX - box.left) / box.width) * W));
  };

  const barSlot = (i: number, k: number) => {
    const inner = Math.max(1, spans[i].b - spans[i].a - 1);
    const w = Math.max(1, inner / series.length - (series.length > 1 ? 1 : 0));
    return { x: spans[i].a + 0.5 + k * (inner / series.length), w };
  };

  return (
    <div className="flex flex-col gap-2 min-w-0">
      <div ref={boxRef} className="relative">
        <svg
          viewBox={`0 0 ${W} ${height}`}
          className="block w-full h-auto overflow-visible outline-none"
          role="group"
          aria-roledescription={bars ? "bar chart" : "line chart"}
          aria-label={`${title}, ${n} buckets, ${series.length} series. The arrow keys move through the buckets.`}
          aria-describedby={liveId}
          tabIndex={0}
          onFocus={() => {
            setKeyboard(true);
            setHover((h) => h ?? (n > 0 ? n - 1 : null));
          }}
          onBlur={() => {
            setKeyboard(false);
            setHover(null);
          }}
          onKeyDown={onKeyDown}
          onMouseMove={onMouseMove}
          onMouseLeave={() => setHover(null)}
        >
          {axis.ticks.map((t) => (
            <g key={t.value}>
              <line
                x1={ML}
                x2={W - MR}
                y1={yAxisPx(t.value)}
                y2={yAxisPx(t.value)}
                stroke="var(--color-chart-grid)"
                strokeWidth={t.value === 0 ? 1 : 0.5}
              />
              <text
                x={ML - 6}
                y={yAxisPx(t.value)}
                textAnchor="end"
                dominantBaseline="middle"
                fill="var(--color-muted)"
                className="font-mono text-small"
              >
                {t.label}
              </text>
            </g>
          ))}
          {ticks.map((t) => (
            <g key={t.at}>
              <line x1={t.px} x2={t.px} y1={PB} y2={PB + 4} stroke="var(--color-chart-grid)" strokeWidth={1} />
              <text x={t.px} y={height - 4} textAnchor={t.anchor} fill="var(--color-muted)" className="font-mono text-small">
                {t.label}
              </text>
            </g>
          ))}

          {bars
            ? series.map((s, k) =>
                s.points.map((p, i) => {
                  if (p.v == null) return null;
                  const slot = barSlot(i, k);
                  return (
                    <rect
                      key={`${s.key}-${p.at}`}
                      x={slot.x}
                      y={y(p.v)}
                      width={slot.w}
                      height={Math.max(0, y(0) - y(p.v))}
                      rx={Math.min(2, slot.w / 3)}
                      // The fold is a dashed outline at full strength: half a fill would fall under 3:1 on the card.
                      fill={s.other ? "none" : seriesColour(s)}
                      stroke={s.other ? seriesColour(s) : undefined}
                      strokeDasharray={s.other ? seriesDash(s) : undefined}
                    />
                  );
                }),
              )
            : series.map((s) => {
                const paths = seriesPaths(s.points, bucketMs, to, x, y);
                const stroke = seriesColour(s);
                const width = s.other ? 1.5 : 2;
                return (
                  <g key={String(s.key)}>
                    <path
                      d={paths.solid}
                      fill="none"
                      stroke={stroke}
                      strokeWidth={width}
                      strokeDasharray={seriesDash(s) || undefined}
                      strokeLinejoin="round"
                    />
                    <path d={paths.dashed} fill="none" stroke={stroke} strokeWidth={width} strokeDasharray="3 3" />
                  </g>
                );
              })}

          {shown != null && hoverX != null && (
            <>
              <line x1={hoverX} x2={hoverX} y1={MT} y2={PB} stroke="var(--color-fg)" strokeWidth={1} strokeOpacity={0.35} />
              {series.map((s) => {
                const v = s.points[shown].v;
                return v == null ? null : (
                  <circle
                    key={String(s.key)}
                    cx={hoverX}
                    cy={y(v)}
                    r={3.5}
                    fill={seriesColour(s)}
                    stroke="var(--color-surface)"
                    strokeWidth={2}
                  />
                );
              })}
            </>
          )}
          <rect x={ML} y={0} width={W - ML - MR} height={PB} fill="transparent" />
        </svg>

        {shown != null && hoverX != null && (
          <Tooltip
            left={(hoverX / W) * 100}
            top="4px"
            transform={`translate(${hoverX > W * 0.6 ? "calc(-100% - 12px)" : "12px"}, 0)`}
            lines={lines(shown)}
          />
        )}
        <div id={liveId} role="status" className="sr-only">
          {keyboard && shown != null ? lines(shown).join(", ") : ""}
        </div>
      </div>
      {series.length > 1 && (
        <Legend
          ref={legendRef}
          items={series.map((s) => ({
            key: String(s.key),
            label: s.label,
            colour: seriesColour(s),
            dashed: s.other,
            dash: bars ? undefined : seriesDash(s),
          }))}
        />
      )}
    </div>
  );
}
