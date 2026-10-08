// SPDX-License-Identifier: Apache-2.0
/*
 * One classifier's chart for one call site or one tool: the last 7 days against the baseline, the daily series over
 * the range, and the cases it opened.
 *
 * Every card has the same height whatever it holds, so a grid of them lines up: the rows are fixed, the chart is a
 * fixed height, and the cases strip always keeps room for three lanes. Everything is grey except the delta, which is
 * red when the measure got worse and green when it got better (rateStory.tsx).
 *
 * The chart's coordinates are CSS pixels: the SVG takes its width from the card, measured, so its tick text is the
 * small type size at every card width instead of scaling with it.
 */
import { useId, useLayoutEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import type { ChartCard as Card, ChartDay } from "../../api/types";
import { Badge, cn } from "../../ui";
import {
  axisTitle,
  axisUnitOf,
  countBar,
  dailyArming,
  dayTooltip,
  headlineOf,
  lanesOf,
  laneTooltip,
  percent,
  toAxis,
  xTicks,
  yAxis,
} from "./chartRules";

/** The chart's width before the card is measured, and where it cannot be (a test's DOM has no layout). */
const FALLBACK_W = 520;
const ML = 48;
const MR = 8;
const MT = 10;
const PB = 130;
const H = 150;
const STRIP_H = 40;
/** About half the width of a day label ("Sep 24") in pixels; a tick label that would overflow the edge is anchored to it. */
const TICK_HALF = 22;
const LANE_Y = (k: number) => 4 + k * 12;
const DAY_MS = 86_400_000;

type Point = { i: number; v: number };

/** An SVG path through `points`, broken wherever a day has no value. */
function segmentsPath(points: (Point | null)[], x: (i: number) => number, y: (v: number) => number): string {
  let d = "";
  let pen = false;
  for (const p of points) {
    if (!p) {
      pen = false;
      continue;
    }
    d += `${pen ? "L" : "M"}${x(p.i).toFixed(1)} ${y(p.v).toFixed(1)}`;
    pen = true;
  }
  return d;
}

/** The runs of consecutive days that have a value. */
function runs<T>(items: (T | null)[]): T[][] {
  const out: T[][] = [];
  let run: T[] = [];
  for (const it of items) {
    if (it) run.push(it);
    else if (run.length) {
      out.push(run);
      run = [];
    }
  }
  if (run.length) out.push(run);
  return out;
}

const rateOf = (d: ChartDay): number | null => (d.checked ? (d.flagged ?? 0) / d.checked : null);

/** The width of the element `ref` lands on, in CSS pixels, kept current as it resizes. */
function useWidth() {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(FALLBACK_W);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const read = () => {
      if (el.clientWidth > 0) setWidth(el.clientWidth);
    };
    read();
    const observer = new ResizeObserver(read);
    observer.observe(el);
    return () => observer.disconnect();
  }, []);
  return [ref, width] as const;
}

export function ChartCard({ card, basePath }: { card: Card; basePath: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const [laneHover, setLaneHover] = useState<number | null>(null);
  // Only a keyboard reader hears the days: a mouse moving over the chart would otherwise talk over everything.
  const [keyboard, setKeyboard] = useState(false);
  const [boxRef, W] = useWidth();
  const PW = W - ML - MR;
  const liveId = useId();

  const head = headlineOf(card);
  const unit = axisUnitOf(card);
  const n = card.days.length;
  const step = PW / Math.max(1, n);
  const x = (i: number) => ML + (i + 0.5) * step;

  const rate = card.kind === "rate" ? card.days.map((d, i) => (rateOf(d) == null ? null : { i, v: toAxis(unit, rateOf(d)!) })) : [];
  const p50 = card.kind === "range" ? card.days.map((d, i) => (d.n && d.p50 != null ? { i, v: toAxis(unit, d.p50) } : null)) : [];
  const p95 = card.kind === "range" ? card.days.map((d, i) => (d.n && d.p95 != null ? { i, v: toAxis(unit, d.p95) } : null)) : [];
  const bars = card.kind === "count" ? card.days.map((d) => countBar(card, d)) : [];
  const arming = dailyArming(card);

  const baseRate = card.kind === "rate" && !card.learning && card.baseline?.rate != null ? card.baseline.rate : null;
  const baseBand =
    card.kind === "range" && !card.learning && card.baseline?.p50 != null && card.baseline.p95 != null
      ? { p50: toAxis(unit, card.baseline.p50), p95: toAxis(unit, card.baseline.p95) }
      : null;

  const values = [
    ...rate.map((p) => p?.v ?? 0),
    ...p95.map((p) => p?.v ?? 0),
    ...bars.map((b) => b.height),
    baseRate != null ? toAxis(unit, baseRate) : 0,
    baseBand?.p95 ?? 0,
    arming ?? 0,
  ];
  const dataMax = Math.max(0, ...values);
  const axis = yAxis(unit === "count" ? dataMax : dataMax * 1.08, unit);
  const y = (v: number) => MT + (1 - Math.min(v, axis.top) / axis.top) * (PB - MT);

  const ticks = xTicks(card.days.map((d) => d.date));
  const lastRate = [...rate].reverse().find((p) => p != null) ?? null;

  const hoverY =
    hover == null
      ? null
      : card.kind === "rate"
        ? rate[hover]?.v
        : card.kind === "range"
          ? p95[hover]?.v
          : undefined;

  const move = (to: number) => setHover(Math.max(0, Math.min(n - 1, to)));
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowLeft") move((hover ?? n - 1) - 1);
    else if (e.key === "ArrowRight") move((hover ?? n - 1) + 1);
    else if (e.key === "Home") move(0);
    else if (e.key === "End") move(n - 1);
    else return;
    e.preventDefault();
  };

  const tipSide = (px: number, up: boolean) =>
    `translate(${px > W * 0.6 ? "calc(-100% - 12px)" : "12px"}, ${up ? "calc(-100% - 4px)" : "0"})`;

  const { lanes, more } = lanesOf(card.cases.spans);
  const from = n > 0 ? Date.parse(`${card.days[0].date}T00:00:00Z`) : 0;
  const dayIndex = (iso: string) => Math.round((Date.parse(`${iso.slice(0, 10)}T00:00:00Z`) - from) / DAY_MS);
  const laneBoxes = lanes.map((s, k) => {
    const si = Math.max(0, Math.min(n - 1, dayIndex(s.start_at)));
    const ei = s.end_at == null ? n - 1 : Math.max(si, Math.min(n - 1, dayIndex(s.end_at)));
    const a = x(si) - step / 2;
    const w = Math.max(3, x(ei) + step / 2 - a);
    return { span: s, x: a, w, y: LANE_Y(k), cx: a + w / 2 };
  });
  const openCases = card.cases.open_cases;

  return (
    <section aria-label={card.name} className="rounded-card bg-surface border border-border p-4 flex flex-col gap-2.5 min-w-0">
      <div className="flex items-center gap-2.5 h-7 overflow-hidden">
        <h3 className="text-h3 text-fg truncate">{card.name}</h3>
        {openCases > 0 && (
          <Badge className="shrink-0">{openCases === 1 ? "1 open case" : `${openCases} open cases`}</Badge>
        )}
        {card.learning && <Badge className="shrink-0">Learning</Badge>}
        <span className="flex-1" />
        {card.learning && (
          <span className="flex items-center gap-2 shrink-0 text-small text-muted tabular-nums">
            <span aria-hidden="true" className="block w-20 h-1 rounded-micro bg-border overflow-hidden">
              <span
                className="block h-full bg-fg-secondary"
                style={{ width: `${Math.min(100, (card.learning.learned / Math.max(1, card.learning.needed)) * 100)}%` }}
              />
            </span>
            {`${card.learning.learned.toLocaleString("en-US")} / ${card.learning.needed.toLocaleString("en-US")}`}
          </span>
        )}
      </div>

      <div className="flex items-baseline gap-2.5 h-7 overflow-hidden whitespace-nowrap">
        <span className="text-h2 text-fg tabular-nums">{head.value}</span>
        {head.delta && (
          <span
            className={cn(
              "text-body font-medium tabular-nums",
              head.delta.tone === "worse" ? "text-error" : head.delta.tone === "better" ? "text-success" : "text-muted",
            )}
          >
            {head.delta.text}
          </span>
        )}
        <span className="text-small text-muted truncate">{head.label}</span>
      </div>

      <div className="flex flex-col gap-1">
        <div className="flex justify-between gap-3 text-small text-muted whitespace-nowrap">
          <span className="truncate">{axisTitle(card)}</span>
          <span>Per day, UTC</span>
        </div>

        <div ref={boxRef} className="relative">
          <svg
            viewBox={`0 0 ${W} ${H}`}
            className="block w-full h-auto overflow-visible"
            role="group"
            aria-roledescription="chart"
            aria-label={`${card.name}, ${n === 1 ? "last day" : `last ${n} days`}. The arrow keys move through the days.`}
            aria-describedby={liveId}
            tabIndex={0}
            onFocus={() => {
              setKeyboard(true);
              setHover((h) => h ?? n - 1);
            }}
            onBlur={() => {
              setKeyboard(false);
              setHover(null);
            }}
            onKeyDown={onKeyDown}
          >
            {axis.ticks.map((t) => (
              <g key={t.value}>
                <line
                  x1={ML}
                  x2={W - MR}
                  y1={y(t.value)}
                  y2={y(t.value)}
                  stroke="var(--color-chart-grid)"
                  strokeWidth={t.value === 0 ? 1 : 0.5}
                />
                <text
                  x={ML - 6}
                  y={y(t.value)}
                  textAnchor="end"
                  dominantBaseline="middle"
                  fill="var(--color-muted)"
                  className="font-mono text-small"
                >
                  {t.label}
                </text>
              </g>
            ))}
            {ticks.map((t) => {
              const px = x(t.index);
              const anchor = px + TICK_HALF > W ? "end" : px - TICK_HALF < ML - 6 ? "start" : "middle";
              return (
                <g key={t.index}>
                  <line x1={px} x2={px} y1={PB} y2={PB + 4} stroke="var(--color-chart-grid)" strokeWidth={1} />
                  <text x={px} y={H - 4} textAnchor={anchor} fill="var(--color-muted)" className="font-mono text-small">
                    {t.label}
                  </text>
                </g>
              );
            })}

            {baseBand && (
              <rect
                x={ML}
                y={y(baseBand.p95)}
                width={PW}
                height={Math.max(1, y(baseBand.p50) - y(baseBand.p95))}
                fill="var(--color-fg)"
                fillOpacity={0.06}
                stroke="var(--color-accent-edge)"
                strokeWidth={1}
                strokeDasharray="4 3"
              />
            )}
            {baseRate != null && (
              <line
                x1={ML}
                x2={W - MR}
                y1={y(toAxis(unit, baseRate))}
                y2={y(toAxis(unit, baseRate))}
                stroke="var(--color-accent-edge)"
                strokeWidth={1.25}
                strokeDasharray="4 3"
              />
            )}

            {card.kind === "range" &&
              runs(p50.map((p, i) => (p && p95[i] ? { i, lo: p.v, hi: p95[i]!.v } : null))).map((run) => (
                <path
                  key={`band${run[0].i}`}
                  d={
                    `M${run.map((r) => `${x(r.i).toFixed(1)} ${y(r.hi).toFixed(1)}`).join("L")}` +
                    `L${[...run]
                      .reverse()
                      .map((r) => `${x(r.i).toFixed(1)} ${y(r.lo).toFixed(1)}`)
                      .join("L")}Z`
                  }
                  fill="var(--color-fg)"
                  fillOpacity={0.16}
                />
              ))}
            {card.kind === "range" && (
              <>
                <path d={segmentsPath(p95, x, y)} fill="none" stroke="var(--color-fg-secondary)" strokeWidth={1.25} strokeLinejoin="round" />
                <path
                  d={segmentsPath(p50, x, y)}
                  fill="none"
                  stroke="var(--color-fg)"
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                />
              </>
            )}

            {card.kind === "count" &&
              bars.map(({ height: v, reached }, i) => {
                if (!v) return null;
                const bw = Math.max(1.5, step * 0.7);
                return (
                  <rect
                    key={i}
                    x={x(i) - bw / 2}
                    y={y(v)}
                    width={bw}
                    height={Math.max(0, y(0) - y(v))}
                    rx={2}
                    fill={reached ? "var(--color-fg)" : "var(--color-subtle)"}
                  />
                );
              })}
            {arming != null && (
              <line
                x1={ML}
                x2={W - MR}
                y1={y(arming)}
                y2={y(arming)}
                stroke="var(--color-accent-edge)"
                strokeWidth={1.25}
                strokeDasharray="4 3"
              />
            )}

            {card.kind === "rate" && (
              <path
                d={segmentsPath(rate, x, y)}
                fill="none"
                stroke="var(--color-fg)"
                strokeWidth={2}
                strokeLinejoin="round"
                strokeLinecap="round"
              />
            )}

            {baseBand && (
              <LineLabel x={W - MR} y={y(baseBand.p95) - 4}>
                Baseline
              </LineLabel>
            )}
            {baseRate != null && (
              <LineLabel x={W - MR} y={y(toAxis(unit, baseRate)) - 4}>{`Baseline ${percent(baseRate)}`}</LineLabel>
            )}
            {arming != null && <LineLabel x={W - MR} y={y(arming) - 4}>{`${arming} in a day opens a finding`}</LineLabel>}

            {hover != null && (
              <line x1={x(hover)} x2={x(hover)} y1={MT} y2={PB} stroke="var(--color-fg)" strokeWidth={1} strokeOpacity={0.35} />
            )}
            {hover != null && hoverY != null && (
              <circle cx={x(hover)} cy={y(hoverY)} r={4} fill="var(--color-fg)" stroke="var(--color-surface)" strokeWidth={2} />
            )}
            {lastRate && hover == null && (
              <circle cx={x(lastRate.i)} cy={y(lastRate.v)} r={3.5} fill="var(--color-fg)" stroke="var(--color-surface)" strokeWidth={2} />
            )}

            <g onMouseLeave={() => setHover(null)}>
              {card.days.map((d, i) => (
                <rect
                  key={d.date}
                  x={ML + i * step}
                  y={0}
                  width={step}
                  height={PB}
                  fill="transparent"
                  onMouseEnter={() => setHover(i)}
                />
              ))}
            </g>
          </svg>

          {hover != null && (
            <Tooltip left={(x(hover) / W) * 100} top="4px" transform={tipSide(x(hover), false)} lines={dayTooltip(card, hover)} />
          )}
          <div id={liveId} role="status" className="sr-only">
            {keyboard && hover != null ? dayTooltip(card, hover).join(", ") : ""}
          </div>
        </div>

        <div className="mt-1 pt-1.5 border-t border-border">
          <div className="flex items-center gap-3 h-5 text-small">
            <span className="text-muted">Cases</span>
            {lanes.length === 0 && <span className="text-muted">None in this range</span>}
            <span className="flex-1" />
            {more > 0 && (
              <Link
                to={`${basePath}/triage`}
                className="text-accent hover:text-accent-hover transition-colors"
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                {`+${more} more in Triage`}
              </Link>
            )}
          </div>
          <div className="relative">
            <svg viewBox={`0 0 ${W} ${STRIP_H}`} aria-hidden="true" className="block w-full h-auto">
              {laneBoxes.map((l, k) => (
                <rect
                  key={l.span.finding_id + k}
                  x={l.x}
                  y={l.y}
                  width={l.w}
                  height={6}
                  rx={3}
                  fill={
                    laneHover === k
                      ? "var(--color-fg)"
                      : l.span.end_at == null
                        ? "var(--color-fg-secondary)"
                        : "var(--color-border)"
                  }
                />
              ))}
            </svg>
            {laneBoxes.map((l, k) => (
              <Link
                key={l.span.finding_id + k}
                to={`${basePath}/cases/${encodeURIComponent(l.span.case_id)}`}
                aria-label={`Opens ${l.span.case_reference}: ${l.span.case_title}`}
                className="absolute rounded-pill"
                style={{
                  left: `${(l.x / W) * 100}%`,
                  width: `${(l.w / W) * 100}%`,
                  top: `${((l.y - 3) / STRIP_H) * 100}%`,
                  height: `${(12 / STRIP_H) * 100}%`,
                }}
                onMouseEnter={() => setLaneHover(k)}
                onMouseLeave={() => setLaneHover(null)}
                onFocus={() => setLaneHover(k)}
                onBlur={() => setLaneHover(null)}
              />
            ))}
            {laneHover != null && laneBoxes[laneHover] && (
              <Tooltip
                left={(laneBoxes[laneHover].cx / W) * 100}
                top={`${(laneBoxes[laneHover].y / STRIP_H) * 100}%`}
                transform={tipSide(laneBoxes[laneHover].cx, true)}
                lines={laneTooltip(laneBoxes[laneHover].span)}
              />
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

/** The label of a dashed reference line, ringed in the card's colour so it stays legible over the series. */
function LineLabel({ x, y, children }: { x: number; y: number; children: string }) {
  return (
    <text
      x={x}
      y={y}
      textAnchor="end"
      fill="var(--color-muted)"
      stroke="var(--color-surface)"
      strokeWidth={3}
      paintOrder="stroke"
      className="font-mono text-small"
    >
      {children}
    </text>
  );
}

function Tooltip({ left, top, transform, lines }: { left: number; top: string; transform: string; lines: string[] }) {
  return (
    <div
      role="tooltip"
      className="absolute z-10 pointer-events-none flex flex-col rounded-card bg-overlay border border-border px-2.5 py-2 whitespace-nowrap text-small tabular-nums"
      style={{ left: `${left}%`, top, transform, boxShadow: "var(--shadow-md)" }}
    >
      {lines.map((line, i) => (
        <span key={i} className={i === 0 ? "text-fg" : "text-muted"}>
          {line}
        </span>
      ))}
    </div>
  );
}

