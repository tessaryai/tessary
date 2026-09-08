// SPDX-License-Identifier: Apache-2.0
/*
 * A distribution shift told as a story: what moved, then what is behind it.
 *
 * <h2>What replaced what</h2>
 * The page used to open with three stacked tables of paired readings, each with a caption explaining
 * how to read it. Every finding therefore looked identical, and the one fact a reader needed — which
 * way did this go, and by how much — was somewhere in the third column of the first table.
 *
 * Here the measure's two windows are drawn once, as a range from median to 95th percentile on a log
 * axis, and the two observations worth naming hang off it as numbered pins. The tables stay, below,
 * for the reader who wants the decomposition; they are no longer the argument.
 *
 * <h2>Colour is direction and nothing else</h2>
 * A shift against the reader is `--color-error`, a shift for them is `--color-success`, and every
 * annotation is neutral. Pins carry no tone because a pin marks where to look rather than what to
 * think, and the workload block carries none because workload is the context for the drift, not the
 * drift — tinting it counted the same signal twice.
 *
 * <h2>Log axis, and why there is no alternative</h2>
 * A single cost finding here runs $0.59 at the median against $18.11 at the tail. On a linear axis
 * the median of both windows collapses onto the left edge and the picture says only "the tail is
 * big", which is true of every finding this detector produces.
 */
import type { components } from "../../api/generated/schema";
import { cn } from "../../ui";
import { PairBlock } from "./findingCharts";

type Shift = components["schemas"]["ShiftDetail"];
type Pair = components["schemas"]["Pair"];

/** A shift that moved against the reader, or for them. Both measures here are lower-is-better. */
export type Tone = "negative" | "positive";

export function toneOf(shift: Shift): Tone {
  return shift.direction === "down" ? "positive" : "negative";
}

function toneVar(tone: Tone): string {
  return tone === "positive" ? "var(--color-success)" : "var(--color-error)";
}

export function toneTextClass(tone: Tone): string {
  return tone === "positive" ? "text-success" : "text-error";
}

function pairOf(pairs: Pair[], key: string): Pair | null {
  return pairs.find((p) => p.key === key) ?? null;
}

/**
 * The measure at the precision a reader can hold, in the unit it is actually in.
 *
 * <p>Durations arrive in milliseconds and span five orders of magnitude across the findings this
 * detector opens, so a fixed unit prints either `0.02s` or `4502356ms`. The unit is chosen per value.
 */
export function formatMeasure(measure: string, v: number | null | undefined): string {
  if (v == null) return "–";
  if (measure === "cost") return `$${v >= 100 ? v.toFixed(0) : v >= 1 ? v.toFixed(2) : v.toPrecision(2)}`;
  if (v >= 60_000) return `${trimZeros((v / 60_000).toFixed(1))}min`;
  if (v >= 1_000) return `${trimZeros((v / 1_000).toFixed(v >= 10_000 ? 0 : 2))}s`;
  return `${Math.round(v)}ms`;
}

/** `1.00` → `1`. Axis ticks land on round numbers and should not print as though they did not. */
function trimZeros(s: string): string {
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
}

/**
 * What the left-hand window IS, in words rather than in the wire value.
 *
 * <p>`previous` has to be spelled out rather than passed through. It has not meant "the window before
 * this one" since a rolling control replaced that: it is a weighted merge of the last three weeks of
 * closed windows with confirmed-regression days left out. The wire word stayed `previous` because it
 * is the last segment of every cause key ever written and renaming it would split one bucket's history
 * in two, which is exactly why a reader must not be shown it raw.
 */
export function referenceWords(reference: string): string {
  return reference === "previous" ? "its recent normal" : "the pinned baseline";
}

/** The measure as a person names it. `tool_duration` is a duration; the bucket says whose. */
export function measureNoun(measure: string): string {
  if (measure === "cost") return "Cost per turn";
  if (measure === "tool_duration") return "Time per call";
  if (measure === "duration") return "Time per turn";
  return measure.replace(/_/g, " ");
}

function pctChange(then: number, now: number): number {
  return ((now - then) / Math.abs(then)) * 100;
}

/** `+67%` / `96% faster` / `6.8× more`, whichever states the size of this move most plainly. */
function moveWords(then: number, now: number, tone: Tone, measure: string): string {
  const ratio = now / then;
  if (ratio >= 2) return `${ratio.toFixed(1)}× more`;
  if (ratio > 0 && ratio <= 0.5) {
    const times = 1 / ratio;
    return measure === "cost" ? `${times.toFixed(1)}× cheaper` : `${times.toFixed(0)}× faster`;
  }
  const pct = pctChange(then, now);
  const verb = measure === "cost" ? (pct >= 0 ? "more" : "less") : pct >= 0 ? "slower" : "faster";
  void tone;
  return `${Math.abs(pct).toFixed(0)}% ${verb}`;
}

/**
 * The two observations worth pinning on the chart, in the order a reader meets them.
 *
 * <p>The pair is normally the median and the tail, because those are the two numbers the chart draws.
 * The exception is a reference window whose median and tail sit within a few percent of each other:
 * real work does not distribute like that, so the flat window is the observation and it displaces the
 * tail. That is the only special case, and it earns its place — it is how a timeout reads in this data,
 * and no other view in the product would ever say so.
 */
type Pin = { label: string; detail: string; at: "before-p50" | "after-p50" | "after-p95" };

const FLAT_WINDOW_RATIO = 1.15;

export function pinsFor(shift: Shift): Pin[] {
  const p50 = pairOf(shift.quantiles, "p50");
  const p95 = pairOf(shift.quantiles, "p95");
  if (!p50?.then || !p50.now) return [];
  const m = shift.measure;
  const tone = toneOf(shift);
  const unit = m === "cost" ? "turn" : "call";

  const median: Pin = {
    label: `A typical ${unit} is ${moveWords(p50.then, p50.now, tone, m)}`,
    detail: `${formatMeasure(m, p50.then)} to ${formatMeasure(m, p50.now)} at the median. This is the number that fired the finding.`,
    at: "after-p50",
  };

  const flat = p95?.then != null && p95.then / p50.then < FLAT_WINDOW_RATIO;
  if (flat && p95?.then != null) {
    const spread = ((p95.then / p50.then - 1) * 100).toFixed(0);
    return [
      {
        label: `Before, every ${unit} took almost exactly the same time`,
        detail: `Median ${formatMeasure(m, p50.then)} and p95 ${formatMeasure(m, p95.then)}, a ${spread}% spread across the whole window. Work doesn't distribute like that on its own. Something was hitting a limit.`,
        at: "before-p50",
      },
      median,
    ];
  }

  if (p95?.then == null || p95.now == null) return [median];

  const tailPct = pctChange(p95.then, p95.now);
  const medPct = pctChange(p50.then, p50.now);
  const tailLabel =
    Math.abs(tailPct) > Math.abs(medPct) * 2
      ? `The tail moved ${moveWords(p95.then, p95.now, tone, m)}. That's the finding`
      : Math.abs(tailPct) < Math.abs(medPct)
        ? "The expensive tail moved less than the middle"
        : "The tail moved with the middle";
  return [
    median,
    {
      label: tailLabel,
      detail: `${formatMeasure(m, p95.then)} to ${formatMeasure(m, p95.now)} at p95, against ${medPct >= 0 ? "+" : ""}${medPct.toFixed(0)}% at the median.`,
      at: "after-p95",
    },
  ];
}

/* ------------------------------------------------------------------ chart */

const PLOT_LEFT = 70;
const PLOT_RIGHT = 770;
const VIEW_W = 800;
const VIEW_H = 196;

/** 1/2/5 × 10^k ticks inside the domain, which is what a log axis reads well at. */
function logTicks(lo: number, hi: number): number[] {
  const out: number[] = [];
  const startExp = Math.floor(Math.log10(lo));
  for (let e = startExp; e <= Math.ceil(Math.log10(hi)); e++) {
    for (const mult of [1, 2, 5]) {
      const v = mult * 10 ** e;
      if (v >= lo && v <= hi) out.push(v);
    }
  }
  return out.length >= 2 ? out : [lo, hi];
}

/**
 * Both windows as a range from median to 95th percentile, on one shared log axis.
 *
 * <p>Two rows, not two series: these are not samples of a trajectory, they are the reference window
 * and the one that just closed. Drawing them as a line would imply a path between them that nobody
 * measured.
 */
export function ShiftChart({ shift }: { shift: Shift }) {
  const p50 = pairOf(shift.quantiles, "p50");
  const p95 = pairOf(shift.quantiles, "p95");
  if (!p50?.then || !p50.now) return null;

  const values = [p50.then, p50.now, p95?.then, p95?.now].filter((v): v is number => v != null && v > 0);
  const lo = Math.min(...values) / 1.6;
  const hi = Math.max(...values) * 1.3;
  const span = Math.log10(hi) - Math.log10(lo);
  const x = (v: number) => PLOT_LEFT + ((Math.log10(v) - Math.log10(lo)) / span) * (PLOT_RIGHT - PLOT_LEFT);

  const tone = toneVar(toneOf(shift));
  const m = shift.measure;
  const pins = pinsFor(shift);
  const pinX = (at: Pin["at"]) =>
    at === "before-p50" ? x(p50.then as number) : at === "after-p50" ? x(p50.now as number) : x((p95?.now ?? p50.now) as number);
  const pinRowY = (at: Pin["at"]) => (at === "before-p50" ? 70 : 118);

  const beforeEnd = p95?.then != null ? x(p95.then) : x(p50.then);
  const afterEnd = p95?.now != null ? x(p95.now) : x(p50.now);
  // A reference window whose quantiles nearly coincide draws as a stub, so one label serves both.
  const beforeIsStub = beforeEnd - x(p50.then) < 24;

  return (
    <svg
      viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
      className="w-full h-auto block"
      role="img"
      aria-label={`${measureNoun(m)}: median ${formatMeasure(m, p50.then)} to ${formatMeasure(m, p50.now)}, 95th percentile ${formatMeasure(m, p95?.then)} to ${formatMeasure(m, p95?.now)}`}
    >
      {logTicks(lo, hi).map((t) => (
        <line key={t} x1={x(t)} y1={40} x2={x(t)} y2={168} stroke="var(--color-chart-grid)" strokeWidth={1} />
      ))}

      {pins.map((pin, i) => (
        <line
          key={`c${i}`}
          x1={pinX(pin.at)}
          y1={34}
          x2={pinX(pin.at)}
          y2={pinRowY(pin.at)}
          stroke="var(--color-border-strong)"
          strokeWidth={1}
          strokeDasharray="2 3"
        />
      ))}

      <text x={0} y={82} fontSize={11} fill="var(--color-subtle)" className="font-mono">
        before
      </text>
      <line
        x1={x(p50.then)}
        y1={78}
        x2={beforeEnd}
        y2={78}
        stroke="var(--color-border-strong)"
        strokeWidth={8}
        strokeLinecap="round"
      />
      <circle cx={x(p50.then)} cy={78} r={5.5} fill="var(--color-muted)" />
      {p95?.then != null && !beforeIsStub && (
        <line x1={beforeEnd} y1={70} x2={beforeEnd} y2={86} stroke="var(--color-subtle)" strokeWidth={2} />
      )}
      {beforeIsStub ? (
        <text
          x={(x(p50.then) + beforeEnd) / 2}
          y={62}
          textAnchor="middle"
          fontSize={11}
          fill="var(--color-muted)"
          className="font-mono">
          {formatMeasure(m, p50.then)} – {formatMeasure(m, p95?.then)}
        </text>
      ) : (
        <>
          <text x={x(p50.then)} y={62} textAnchor="middle" fontSize={11} fill="var(--color-muted)" className="font-mono">
            {formatMeasure(m, p50.then)}
          </text>
          {p95?.then != null && (
            <text x={beforeEnd} y={62} textAnchor="middle" fontSize={11} fill="var(--color-subtle)" className="font-mono">
              {formatMeasure(m, p95.then)}
            </text>
          )}
        </>
      )}

      <text x={0} y={130} fontSize={11} fill="var(--color-muted)" className="font-mono">
        after
      </text>
      <line x1={x(p50.now)} y1={126} x2={afterEnd} y2={126} stroke={tone} strokeWidth={8} strokeLinecap="round" opacity={0.35} />
      <circle cx={x(p50.now)} cy={126} r={5.5} fill={tone} />
      {p95?.now != null && <line x1={afterEnd} y1={118} x2={afterEnd} y2={134} stroke={tone} strokeWidth={2} />}
      <text x={x(p50.now)} y={152} textAnchor="middle" fontSize={11} fill={tone} className="font-mono">
        {formatMeasure(m, p50.now)}
      </text>
      {p95?.now != null && afterEnd - x(p50.now) > 24 && (
        <text
          x={Math.min(afterEnd, PLOT_RIGHT - 6)}
          y={152}
          textAnchor={afterEnd > PLOT_RIGHT - 40 ? "end" : "middle"}
          fontSize={11}
          fill={tone}
          className="font-mono">
          {formatMeasure(m, p95.now)}
        </text>
      )}

      {pins.map((pin, i) => (
        <g key={`p${i}`}>
          <circle cx={pinX(pin.at)} cy={22} r={10} fill="var(--color-border-strong)" />
          <text
            x={pinX(pin.at)}
            y={26}
            textAnchor="middle"
            fontSize={11}
            fill="var(--color-fg)"
            className="font-mono">
            {i + 1}
          </text>
        </g>
      ))}

      <line x1={PLOT_LEFT} y1={168} x2={PLOT_RIGHT} y2={168} stroke="var(--color-chart-grid)" strokeWidth={1} />
      {logTicks(lo, hi).map((t) => (
        <text
          key={`t${t}`}
          x={x(t)}
          y={186}
          textAnchor="middle"
          fontSize={10.5}
          fill="var(--color-subtle)"
          className="font-mono">
          {formatMeasure(m, t)}
        </text>
      ))}
    </svg>
  );
}

/** What each pin means, in the order the chart numbers them. */
export function ShiftPins({ shift }: { shift: Shift }) {
  const pins = pinsFor(shift);
  if (pins.length === 0) return null;
  return (
    <div className="rounded-card border border-border overflow-hidden flex flex-col">
      {pins.map((pin, i) => (
        <div
          key={pin.at}
          className={cn("flex items-start gap-3 bg-surface py-3.25 px-4", i > 0 && "border-t border-border")}
          
        >
          <span
            className="rounded-pill bg-border-strong text-fg font-mono flex items-center justify-center shrink-0 text-label"
            style={{ width: 20, height: 20 }}
          >
            {i + 1}
          </span>
          <div className="flex flex-col min-w-0 gap-0.5">
            <span className="text-body font-medium text-fg">
              {pin.label}
            </span>
            <span className="text-muted text-small">
              {pin.detail}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------- what is behind it */

function hasReadings(pairs: Pair[]): boolean {
  return pairs.some((p) => p.then != null || p.now != null);
}

/**
 * The decomposition, when there is one.
 *
 * <p>A tool-duration shift has neither tokens nor a user message, so this renders the window instead
 * of two empty tables under a heading that promises an explanation. An empty section is worse than no
 * section: it reads as a bug rather than as the absence of a measurement.
 */
export function ShiftBehind({ shift }: { shift: Shift }) {
  const tokens = hasReadings(shift.tokens);
  const workload = hasReadings(shift.workload);

  // Neither block has a reading, which is not a gap: a tool call carries no tokens and no user
  // message, so there is nothing to decompose. The window and its sample sizes already say themselves
  // in the line under the heading, so this renders nothing rather than an empty section, or a strip
  // repeating what the reader just read.
  if (!tokens && !workload) return null;

  return (
    <section className="mt-6.5">
      <div className="flex items-baseline gap-3">
        <h2 className="font-mono text-label uppercase text-muted">What is behind it</h2>
        <span className="text-subtle text-small">
          {tokens && workload ? "tokens, and what users asked for" : tokens ? "tokens" : "what users asked for"}
        </span>
      </div>
      <div className="grid gap-4 mt-3" style={{ gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
        {tokens && (
          <PairBlock
            title="Tokens"
            pairs={shift.tokens}
            emphasiseKey={dominantKey(shift.tokens)}
            emphasiseTone={toneOf(shift)}
          />
        )}
        {workload && (
          <PairBlock
            title="What users asked for"
            pairs={shift.workload}
            caption="If these stay flat while the measure moves, the agent changed. If they move with it, the traffic did."
          />
        )}
      </div>
    </section>
  );
}

/**
 * The reading that carries the shift: the largest move in ABSOLUTE terms, not in percent.
 *
 * <p>Percent picks the wrong row every time on a token block. Input tokens going 30 → 91 is +203% and
 * costs nothing; cache reads going 1.21M → 2.39M is +98% and is the entire finding. The reader wants
 * the row that moved the money, and on a block whose rows share a unit that is simply the biggest
 * delta.
 */
function dominantKey(pairs: Pair[]): string | undefined {
  let best: { key: string; size: number } | null = null;
  for (const p of pairs) {
    if (p.then == null || p.now == null) continue;
    const size = Math.abs(p.now - p.then);
    if (!best || size > best.size) best = { key: p.key, size };
  }
  return best?.key;
}
