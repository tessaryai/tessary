// SPDX-License-Identifier: Apache-2.0
// The two windows drawn as median-to-p95 ranges on a log axis: a cost finding can run $0.59 at the median
// and $18.11 at the tail, which a linear axis collapses. Colour is direction only; pins and the
// workload block carry none.
import type { components } from "../../api/generated/schema";
import { cn } from "../../ui";
import { PairBlock } from "./findingCharts";

type Shift = components["schemas"]["ShiftDetail"];
type Pair = components["schemas"]["Pair"];

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

/** Durations span five orders of magnitude, so the unit is chosen per value. */
export function formatMeasure(measure: string, v: number | null | undefined): string {
  if (v == null) return "–";
  if (measure === "cost") return `$${v >= 100 ? v.toFixed(0) : v >= 1 ? v.toFixed(2) : v.toPrecision(2)}`;
  if (v >= 60_000) return `${trimZeros((v / 60_000).toFixed(1))}min`;
  if (v >= 1_000) return `${trimZeros((v / 1_000).toFixed(v >= 10_000 ? 0 : 2))}s`;
  return `${Math.round(v)}ms`;
}

/** A difference in the measure's units, always signed: `+1.5s`, `-$0.30`. */
export function signedMeasure(measure: string, v: number | null | undefined): string {
  if (v == null) return "–";
  return `${v < 0 ? "-" : "+"}${formatMeasure(measure, Math.abs(v))}`;
}

function trimZeros(s: string): string {
  return s.includes(".") ? s.replace(/\.?0+$/, "") : s;
}

/**
 * The wire word `previous` is a weighted merge of the last three weeks of closed windows with confirmed
 * regression days left out; it stays in every cause key, so it is never shown raw.
 */
export function referenceWords(reference: string): string {
  return reference === "previous" ? "its recent normal" : "the pinned baseline";
}

export function measureNoun(measure: string): string {
  if (measure === "cost") return "Cost per turn";
  if (measure === "tool_duration") return "Time per call";
  if (measure === "duration") return "Time per turn";
  return measure.replace(/_/g, " ");
}

function pctChange(then: number, now: number): number {
  return ((now - then) / Math.abs(then)) * 100;
}

function moveWords(then: number, now: number, measure: string): string {
  const ratio = now / then;
  if (ratio >= 2) return `${ratio.toFixed(1)}× more`;
  if (ratio > 0 && ratio <= 0.5) {
    const times = 1 / ratio;
    return measure === "cost" ? `${times.toFixed(1)}× cheaper` : `${times.toFixed(0)}× faster`;
  }
  const pct = pctChange(then, now);
  const verb = measure === "cost" ? (pct >= 0 ? "more" : "less") : pct >= 0 ? "slower" : "faster";
  return `${Math.abs(pct).toFixed(0)}% ${verb}`;
}

/**
 * Normally the median and the tail. A reference window whose two sit within a few percent is flat, which
 * is how a timeout reads, so it displaces the tail.
 */
type Pin = { label: string; detail: string; at: "before-p50" | "after-p50" | "after-p95" };

const FLAT_WINDOW_RATIO = 1.15;

export function pinsFor(shift: Shift): Pin[] {
  const p50 = pairOf(shift.quantiles, "p50");
  const p95 = pairOf(shift.quantiles, "p95");
  if (!p50?.then || !p50.now) return [];
  const m = shift.measure;
  const unit = m === "cost" ? "turn" : "call";

  const median: Pin = {
    label: `A typical ${unit} is ${moveWords(p50.then, p50.now, m)}`,
    detail: `${formatMeasure(m, p50.then)} to ${formatMeasure(m, p50.now)} at the median. This is the number that fired the finding.`,
    at: "after-p50",
  };

  if (p95?.then != null && p95.then / p50.then < FLAT_WINDOW_RATIO) {
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
      ? `The tail moved ${moveWords(p95.then, p95.now, m)}. That's the finding`
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

/** The mean then and now, as a difference in the measure's own units. Null on a finding written before the mean. */
export function meanLine(shift: Shift): { label: string; detail: string; tone: Tone } | null {
  const then = shift.mean?.then;
  const now = shift.mean?.now;
  if (then == null || now == null) return null;
  const m = shift.measure;
  const d = shift.shift?.mean ?? now - then;
  const unit = m === "cost" ? "turn" : "call";
  const verb = m === "cost" ? (d < 0 ? "less" : "more") : d < 0 ? "faster" : "slower";
  return {
    label: `On average a ${unit} is ${formatMeasure(m, Math.abs(d))} ${verb}`,
    detail: `Mean ${formatMeasure(m, then)} to ${formatMeasure(m, now)} (${signedMeasure(m, d)}). p50 ${signedMeasure(
      m,
      shift.shift?.p50,
    )}, p95 ${signedMeasure(m, shift.shift?.p95)}.`,
    tone: d < 0 ? "positive" : "negative",
  };
}

/* ------------------------------------------------------------------ chart */

const PLOT_LEFT = 70;
const PLOT_RIGHT = 770;
const VIEW_W = 800;
const VIEW_H = 196;

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

/** Two rows, not a series: a line would imply a path between windows that nobody measured. */
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
    at === "before-p50" ? x(p50.then as number) : at === "after-p50" ? x(p50.now as number) : x(p95!.now as number);
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

export function ShiftMean({ shift }: { shift: Shift }) {
  const line = meanLine(shift);
  if (!line) return null;
  return (
    <div className="flex flex-col gap-0.5">
      <span className={cn("text-body font-medium", toneTextClass(line.tone))}>{line.label}</span>
      <span className="text-muted text-small">{line.detail}</span>
    </div>
  );
}

/* ------------------------------------------------- what is behind it */

function hasReadings(pairs: Pair[]): boolean {
  return pairs.some((p) => p.then != null || p.now != null);
}

/** A tool-duration shift has no tokens or user message, so this renders the window instead of empty tables. */
export function ShiftBehind({ shift }: { shift: Shift }) {
  const tokens = hasReadings(shift.tokens);
  const workload = hasReadings(shift.workload);

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
 * Absolute, not percent: input tokens 30 → 91 is +203% and costs nothing; cache reads 1.21M → 2.39M is
 * +98% and is the whole finding.
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
