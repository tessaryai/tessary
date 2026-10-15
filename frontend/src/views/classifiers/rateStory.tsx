// SPDX-License-Identifier: Apache-2.0
// A rate is one proportion, so it is drawn twice on a linear axis, never as shiftStory's log range.
// Its change is in percentage points, never percent change (0.30% to 1.72% is "477%" and ten calls).
// Colour is direction only: error when it rose, success when it fell, everything else neutral.
import type { components } from "../../api/generated/schema";
import { cn } from "../../ui";

type Rate = components["schemas"]["RateDetail"];

/** A rate that moved against the reader, or for them. Fewer failures is always better. */
export type RateTone = "negative" | "positive";

export function rateToneOf(rate: Rate): RateTone {
  return rate.curRate > rate.refRate ? "negative" : "positive";
}

function toneVar(tone: RateTone): string {
  return tone === "positive" ? "var(--color-success)" : "var(--color-error)";
}

export function rateToneTextClass(tone: RateTone): string {
  return tone === "positive" ? "text-success" : "text-error";
}

/** Two decimals: these rates live between 0.1% and 10%. */
export function formatRate(r: number): string {
  return `${(r * 100).toFixed(2)}%`;
}

/** The bucket key is already reduced, so this takes its last readable segment; the full key stays in the subtitle. */
export function toolWords(bucketKey: string): string {
  const bare = bucketKey.replace(/^tool:/, "");
  const tail = bare.split("_").filter(Boolean).at(-1) ?? bare;
  return tail.length >= 4 ? tail : bare;
}

const NUMBER_WORDS = [
  "no",
  "one",
  "two",
  "three",
  "four",
  "five",
  "six",
  "seven",
  "eight",
  "nine",
  "ten",
  "eleven",
  "twelve",
];

/**
 * Words up to twelve, then numerals. Below one in a hundred gets the sub-one phrasing, since "no calls"
 * would be a lie. `noun` is the whole countable phrase, so a population of outputs reads "outputs".
 */
export function perHundred(r: number, noun: string = "call"): string {
  const n = r * 100;
  if (n < 1) return `fewer than one ${noun} in every hundred`;
  const rounded = Math.round(n);
  const word = rounded < NUMBER_WORDS.length ? NUMBER_WORDS[rounded] : String(rounded);
  return `${word} ${noun}${rounded === 1 ? "" : "s"} in every hundred`;
}

function upperFirst(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** Per finding, not shared: these rates span 0.3% to 8%, and one axis would draw the small ones as slivers. */
export function axisFor(before: number, after: number): { max: number; ticks: number[] } {
  const top = Math.max(before, after) * 100;
  const ladder = [1, 2, 2.5, 4, 5, 10, 20, 25, 50, 100];
  const max = ladder.find((m) => m >= top * 1.15) ?? 100;
  const divisions = max === 2.5 ? 5 : max % 4 === 0 ? 4 : 5;
  const ticks = Array.from({ length: divisions + 1 }, (_, i) => (max / divisions) * i);
  return { max, ticks };
}

const PLOT_X0 = 70;
const PLOT_X1 = 770;

/** Both bars start at zero: a bar chart with a non-zero baseline lies about ratios. */
export function RateChart({ rate, label = "Failure rate" }: { rate: Rate; label?: string }) {
  const before = rate.refRate * 100;
  const after = rate.curRate * 100;
  const tone = rateToneOf(rate);
  const { max, ticks } = axisFor(rate.refRate, rate.curRate);
  const x = (v: number) => PLOT_X0 + (v / max) * (PLOT_X1 - PLOT_X0);
  const colour = toneVar(tone);
  const tick = (t: number) => (t === 0 ? "0" : `${Number(t.toFixed(2))}%`);

  return (
    <svg
      viewBox="0 0 800 196"
      style={{ width: "100%", height: "auto", display: "block" }}
      role="img"
      aria-label={`${label}: ${formatRate(rate.refRate)} before, ${formatRate(rate.curRate)} after`}
    >
      {ticks.map((t) => (
        <line
          key={t}
          x1={x(t)}
          y1={40}
          x2={x(t)}
          y2={168}
          stroke="var(--color-chart-grid)"
          strokeWidth={1}
        />
      ))}

      <line
        x1={x(before)}
        y1={34}
        x2={x(before)}
        y2={70}
        stroke="var(--color-border-strong)"
        strokeWidth={1}
        strokeDasharray="2 3"
      />
      <line
        x1={x(after)}
        y1={34}
        x2={x(after)}
        y2={118}
        stroke="var(--color-border-strong)"
        strokeWidth={1}
        strokeDasharray="2 3"
      />

      <text x={0} y={82} fontSize={11} fill="var(--color-subtle)" fontFamily="var(--font-mono)">
        before
      </text>
      <line x1={PLOT_X0} y1={78} x2={x(before)} y2={78} stroke="var(--color-border-strong)" strokeWidth={14} />
      <text
        x={x(before)}
        y={62}
        textAnchor="middle"
        fontSize={11}
        fill="var(--color-muted)"
        fontFamily="var(--font-mono)"
      >
        {formatRate(rate.refRate)}
      </text>

      <text x={0} y={130} fontSize={11} fill="var(--color-muted)" fontFamily="var(--font-mono)">
        after
      </text>
      <line x1={PLOT_X0} y1={126} x2={x(after)} y2={126} stroke={colour} strokeWidth={14} opacity={0.85} />
      <text x={x(after)} y={152} textAnchor="middle" fontSize={11} fill={colour} fontFamily="var(--font-mono)">
        {formatRate(rate.curRate)}
      </text>

      <circle cx={x(after)} cy={22} r={10} fill="var(--color-border-strong)" />
      <text x={x(after)} y={26} textAnchor="middle" fontSize={11} fill="var(--color-fg)" fontFamily="var(--font-mono)">
        1
      </text>
      <circle cx={x(before)} cy={22} r={10} fill="var(--color-border-strong)" />
      <text
        x={x(before)}
        y={26}
        textAnchor="middle"
        fontSize={11}
        fill="var(--color-fg)"
        fontFamily="var(--font-mono)"
      >
        2
      </text>

      <line x1={PLOT_X0} y1={168} x2={PLOT_X1} y2={168} stroke="var(--color-chart-grid)" strokeWidth={1} />
      {ticks.map((t) => (
        <text
          key={t}
          x={x(t)}
          y={186}
          textAnchor="middle"
          fontSize={10.5}
          fill="var(--color-subtle)"
          fontFamily="var(--font-mono)"
        >
          {tick(t)}
        </text>
      ))}
    </svg>
  );
}

export function Pin({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <div
      className={cn("flex items-start gap-3 py-3.25 px-4", n > 1 && "border-t border-border")}
      
    >
      <span
        className="font-mono rounded-pill bg-border-strong text-fg flex items-center justify-center shrink-0 text-label"
        style={{ width: 20, height: 20 }}
      >
        {n}
      </span>
      <div className="flex flex-col gap-0.5" style={{ minWidth: 0 }}>
        <span className="text-body font-medium text-fg">
          {title}
        </span>
        <span className="text-muted text-small">
          {children}
        </span>
      </div>
    </div>
  );
}

const DAY_MONTH: Intl.DateTimeFormatOptions = { day: "numeric", month: "long" };

/** Below this many failures, the finding is a lead rather than a result. See {@link RatePins}. */
const FEW_FAILURES = 20;

export function PinList({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-col rounded-card border border-border bg-surface mt-2.75">
      {children}
    </div>
  );
}

/**
 * Pin 1 is the flagged window, the number that fired the finding. Pin 2 carries the sample size: a rate
 * over 500 calls and one over 13,000 are different claims.
 */
export function RatePins({ rate, unit }: { rate: Rate; unit?: string }) {
  const tool = toolWords(rate.bucketKey);
  const headlineNoun = unit ?? (tool ? `${tool} call` : "call");
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleDateString(undefined, DAY_MONTH) : null;
  const rose = rate.curRate > rate.refRate;
  const points = Math.abs(rate.deltaPp);

  return (
    <PinList>
      <Pin n={1} title={`${upperFirst(perHundred(rate.curRate, headlineNoun))} fail now`}>
        {rate.failuresCur.toLocaleString()} failure{rate.failuresCur === 1 ? "" : "s"} across the{" "}
        {rate.nCur.toLocaleString()} calls{since ? ` since ${since}` : ""}. This is the number that fired
        the finding.
      </Pin>
      <Pin n={2} title={`It was ${perHundred(rate.refRate)}`}>
        {formatRate(rate.refRate)} across the {rate.nRef.toLocaleString()} calls this classifier was fitted
        on, so the gap is {points.toFixed(2)} percentage point{points === 1 ? "" : "s"}{" "}
        {rose ? "wider" : "narrower"} than it was.
        {/* The detector's statistic is not on the wire; the failure count is the honest stand-in, since a rate on
           a dozen failures moves as far on a bad afternoon as on a regression. */}
        {rate.failuresCur < FEW_FAILURES && (
          <>
            {" "}
            The whole finding rests on {rate.failuresCur} of them, so one bad afternoon would move this
            as much as a real regression would. Treat it as a lead rather than a result.
          </>
        )}
      </Pin>
    </PinList>
  );
}
