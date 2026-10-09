// SPDX-License-Identifier: Apache-2.0
/*
 * One classifier's chart for one call site or one tool: the last 7 days against the baseline, the series over the
 * range on a continuous UTC time axis, and the cases it opened. A rate or range point is a step held over the hours it
 * merged; a count point is a bar over its bucket.
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
import type { ChartCard as Card, ChartPoint, ClassifierCharts } from "../../api/types";
import { Badge, cn } from "../../ui";
import {
  armingLine,
  axisTitle,
  axisUnitOf,
  countBar,
  headlineOf,
  lanesOf,
  laneTooltip,
  MAX_LANES,
  percent,
  pointTooltip,
  stepPaths,
  type Step,
  timeAxis,
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
const STRIP_H = 36;
/** About half the width of a day label ("Sep 24") in pixels; a tick label that would overflow the edge is anchored to it. */
const TICK_HALF = 22;
const LANE_Y = (k: number) => 4 + k * 12;
/** The vertical centre of lane k: the strip's labels sit on it, so "Cases" lines up with the first bar. */
const LANE_MID = (k: number) => LANE_Y(k) + 3;
const STRIP_TEXT = "absolute -translate-y-1/2 text-small leading-none whitespace-nowrap";

const rateOf = (p: ChartPoint): number | null => (p.checked ? (p.flagged ?? 0) / p.checked : null);

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

/** The index of the point whose span holds `px`, else the one nearest it. */
function nearestPoint(spans: { a: number; b: number }[], px: number): number | null {
  let best: number | null = null;
  let gap = Infinity;
  spans.forEach(({ a, b }, i) => {
    const d = px < a ? a - px : px > b ? px - b : 0;
    if (d < gap) {
      gap = d;
      best = i;
    }
  });
  return best;
}

export function ChartCard({
  card,
  range,
  basePath,
}: {
  card: Card;
  range: Pick<ClassifierCharts, "days" | "from_day" | "to_day">;
  basePath: string;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const [laneHover, setLaneHover] = useState<number | null>(null);
  // Only a keyboard reader hears the points: a mouse moving over the chart would otherwise talk over everything.
  const [keyboard, setKeyboard] = useState(false);
  const [boxRef, W] = useWidth();
  const PW = W - ML - MR;
  const liveId = useId();

  const head = headlineOf(card);
  const unit = axisUnitOf(card);
  const points = card.points;
  const n = points.length;
  const time = timeAxis(card, range.from_day, Date.now());
  const clampT = (t: number) => Math.max(time.from, Math.min(time.to, t));
  const x = (t: number) => ML + ((clampT(t) - time.from) / (time.to - time.from)) * PW;
  const spans = points.map((p) => ({ a: x(Date.parse(p.start_at)), b: x(Date.parse(p.end_at)) }));
  const stepOf = (p: ChartPoint, v: number): Step => ({ start: Date.parse(p.start_at), end: Date.parse(p.end_at), v, open: p.open });

  const rate = card.kind === "rate" ? points.map((p) => (rateOf(p) == null ? null : toAxis(unit, rateOf(p)!))) : [];
  const p50 = card.kind === "range" ? points.map((p) => (p.n && p.p50 != null ? toAxis(unit, p.p50) : null)) : [];
  const p95 = card.kind === "range" ? points.map((p) => (p.n && p.p95 != null ? toAxis(unit, p.p95) : null)) : [];
  const steps = (values: (number | null)[]) =>
    points.flatMap((p, i) => (values[i] == null ? [] : [stepOf(p, values[i]!)]));
  const bars = card.kind === "count" ? points.map((p) => countBar(p)) : [];
  const arming = card.kind === "count" ? armingLine(card) : null;

  const baseRate = card.kind === "rate" && !card.learning && card.baseline?.rate != null ? card.baseline.rate : null;
  const baseBand =
    card.kind === "range" && !card.learning && card.baseline?.p50 != null && card.baseline.p95 != null
      ? { p50: toAxis(unit, card.baseline.p50), p95: toAxis(unit, card.baseline.p95) }
      : null;

  const values = [
    ...rate.map((v) => v ?? 0),
    ...p95.map((v) => v ?? 0),
    ...bars.map((b) => b.height),
    baseRate != null ? toAxis(unit, baseRate) : 0,
    baseBand?.p95 ?? 0,
    arming?.threshold ?? 0,
  ];
  const dataMax = Math.max(0, ...values);
  const axis = yAxis(unit === "count" ? dataMax : dataMax * 1.08, unit);
  const y = (v: number) => MT + (1 - Math.min(v, axis.top) / axis.top) * (PB - MT);

  const ticks = xTicks(range.from_day, range.to_day);
  const lastRate = rate.length > 0 && rate[n - 1] != null ? { x: spans[n - 1].b, v: rate[n - 1]! } : null;
  const ratePaths = card.kind === "rate" ? stepPaths(steps(rate), x, y) : null;
  const p50Paths = card.kind === "range" ? stepPaths(steps(p50), x, y) : null;
  const p95Paths = card.kind === "range" ? stepPaths(steps(p95), x, y) : null;

  // The card can get fewer points while a point is hovered (new data, another range): drop a hover past the last.
  const shown = hover != null && hover < n ? hover : null;
  const hoverX = shown == null ? null : (spans[shown].a + spans[shown].b) / 2;
  const hoverY =
    shown == null ? null : card.kind === "rate" ? rate[shown] : card.kind === "range" ? p95[shown] : null;

  const move = (to: number) => setHover(Math.max(0, Math.min(n - 1, to)));
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (n === 0) return;
    if (e.key === "ArrowLeft") move((shown ?? n - 1) - 1);
    else if (e.key === "ArrowRight") move((shown ?? n - 1) + 1);
    else if (e.key === "Home") move(0);
    else if (e.key === "End") move(n - 1);
    else return;
    e.preventDefault();
  };
  const onMouseMove = (e: React.MouseEvent<SVGRectElement>) => {
    const box = e.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!box || box.width === 0) return;
    setHover(nearestPoint(spans, ((e.clientX - box.left) / box.width) * W));
  };

  const tipSide = (px: number, up: boolean) =>
    `translate(${px > W * 0.6 ? "calc(-100% - 12px)" : "12px"}, ${up ? "calc(-100% - 4px)" : "0"})`;

  const { lanes, more } = lanesOf(card.cases.spans);
  const laneBoxes = lanes.map((s, k) => {
    const a = x(Date.parse(s.start_at));
    const w = Math.max(3, x(s.end_at == null ? time.to : Date.parse(s.end_at)) - a);
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
          <span>UTC</span>
        </div>

        <div ref={boxRef} className="relative">
          <svg
            viewBox={`0 0 ${W} ${H}`}
            className="block w-full h-auto overflow-visible"
            role="group"
            aria-roledescription="chart"
            aria-label={`${card.name}, last ${range.days} days, ${n === 1 ? "1 point" : `${n} points`}. The arrow keys move through the points.`}
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
              const px = x(t.at);
              const anchor = px + TICK_HALF > W ? "end" : px - TICK_HALF < ML - 6 ? "start" : "middle";
              return (
                <g key={t.at}>
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
              points.map((p, i) =>
                p50[i] == null || p95[i] == null ? null : (
                  <rect
                    key={p.start_at}
                    x={spans[i].a}
                    y={y(p95[i]!)}
                    width={Math.max(0.5, spans[i].b - spans[i].a)}
                    height={Math.max(0, y(p50[i]!) - y(p95[i]!))}
                    fill="var(--color-fg)"
                    fillOpacity={p.open ? 0.08 : 0.16}
                  />
                ),
              )}
            {p95Paths && <StepLine paths={p95Paths} stroke="var(--color-fg-secondary)" width={1.25} />}
            {p50Paths && <StepLine paths={p50Paths} stroke="var(--color-fg)" width={2} />}

            {card.kind === "count" &&
              bars.map(({ height: v, reached }, i) => {
                if (!v) return null;
                const inset = spans[i].b - spans[i].a > 2 ? 0.5 : 0;
                const bw = Math.max(1, spans[i].b - spans[i].a - 2 * inset);
                return (
                  <rect
                    key={points[i].start_at}
                    x={spans[i].a + inset}
                    y={y(v)}
                    width={bw}
                    height={Math.max(0, y(0) - y(v))}
                    rx={Math.min(2, bw / 3)}
                    fill={reached ? "var(--color-fg)" : "var(--color-subtle)"}
                  />
                );
              })}
            {arming != null && (
              <line
                x1={ML}
                x2={W - MR}
                y1={y(arming.threshold)}
                y2={y(arming.threshold)}
                stroke="var(--color-accent-edge)"
                strokeWidth={1.25}
                strokeDasharray="4 3"
              />
            )}

            {ratePaths && <StepLine paths={ratePaths} stroke="var(--color-fg)" width={2} />}

            {baseBand && (
              <LineLabel x={W - MR} y={y(baseBand.p95) - 4}>
                Baseline
              </LineLabel>
            )}
            {baseRate != null && (
              <LineLabel x={W - MR} y={y(toAxis(unit, baseRate)) - 4}>{`Baseline ${percent(baseRate)}`}</LineLabel>
            )}
            {arming != null && <LineLabel x={W - MR} y={y(arming.threshold) - 4}>{arming.label}</LineLabel>}

            {hoverX != null && (
              <line x1={hoverX} x2={hoverX} y1={MT} y2={PB} stroke="var(--color-fg)" strokeWidth={1} strokeOpacity={0.35} />
            )}
            {hoverX != null && hoverY != null && (
              <circle cx={hoverX} cy={y(hoverY)} r={4} fill="var(--color-fg)" stroke="var(--color-surface)" strokeWidth={2} />
            )}
            {lastRate && shown == null && (
              <circle cx={lastRate.x} cy={y(lastRate.v)} r={3.5} fill="var(--color-fg)" stroke="var(--color-surface)" strokeWidth={2} />
            )}

            <rect
              x={ML}
              y={0}
              width={PW}
              height={PB}
              fill="transparent"
              onMouseMove={onMouseMove}
              onMouseLeave={() => setHover(null)}
            />
          </svg>

          {shown != null && hoverX != null && (
            <Tooltip left={(hoverX / W) * 100} top="4px" transform={tipSide(hoverX, false)} lines={pointTooltip(card, shown)} />
          )}
          <div id={liveId} role="status" className="sr-only">
            {keyboard && shown != null ? pointTooltip(card, shown).join(", ") : ""}
          </div>
        </div>

        <div className="mt-1 pt-1.5 border-t border-border">
          <div className="relative">
            <span className={`${STRIP_TEXT} text-muted`} style={{ left: 0, top: LANE_MID(0) }}>
              Cases
            </span>
            {lanes.length === 0 && (
              <span className={`${STRIP_TEXT} text-muted`} style={{ left: ML, top: LANE_MID(0) }}>
                None in this range
              </span>
            )}
            {more > 0 && (
              <Link
                to={`${basePath}/triage`}
                className={`${STRIP_TEXT} text-accent hover:text-accent-hover transition-colors`}
                style={{ left: ML, top: LANE_MID(MAX_LANES - 1), transitionDuration: "var(--duration-micro)" }}
              >
                {`+${more} more in Triage`}
              </Link>
            )}
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

/** A step series: the closed points solid, the open one dashed. */
function StepLine({ paths, stroke, width }: { paths: { solid: string; dashed: string }; stroke: string; width: number }) {
  return (
    <>
      {paths.solid && (
        <path d={paths.solid} fill="none" stroke={stroke} strokeWidth={width} strokeLinejoin="round" strokeLinecap="round" />
      )}
      {paths.dashed && (
        <path d={paths.dashed} fill="none" stroke={stroke} strokeWidth={width} strokeLinejoin="round" strokeDasharray="3 3" />
      )}
    </>
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

