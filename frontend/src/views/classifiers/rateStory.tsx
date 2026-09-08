// SPDX-License-Identifier: Apache-2.0
/*
 * A rate shift told as a story: what share of calls fails now, against what it used to.
 *
 * <h2>Why this is not {@link ./shiftStory}</h2>
 * A distribution shift has a distribution — a median and a tail that both moved — and its figure is a
 * range on a log axis. A rate has neither. It is one proportion of one population, so the only honest
 * picture is that proportion drawn twice, on a linear axis, as a share of the calls it was measured
 * over. Reusing the log range chart here would draw two points and imply a spread nobody measured.
 *
 * <h2>Percentage points, never percent change</h2>
 * This page never prints the percent change of a rate. `0.30%` to `1.72%` is a 477% rise and that
 * number is worthless: it is ten failed calls. The change of a percentage is stated in PERCENTAGE
 * POINTS, and the headline reading is a count per hundred calls, which is the form a reader can act
 * on. The old page printed `+94%` in a column labelled "Change" and it was the most prominent number
 * on the screen.
 *
 * <h2>Colour is direction and nothing else</h2>
 * A rate that rose is `--color-error`, one that fell is `--color-success`, and every annotation —
 * pins, axis, the reference bar — is neutral. There is no third colour for a weak result: a finding
 * that only just cleared its threshold says so in words, because tinting uncertainty invents a
 * category the detector does not have.
 */
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

/** `0.024876` → `2.49%`. Two decimals throughout: these rates live between 0.1% and 10%. */
export function formatRate(r: number): string {
  return `${(r * 100).toFixed(2)}%`;
}

/**
 * `tool:mcp_claude_in_chrome_navigate` → `navigate`.
 *
 * <p>The bucket key is already a reduction of the tool's real name (lowercased, ids stripped,
 * punctuation collapsed), so this only takes the last readable segment for use mid-sentence. The full
 * key stays on the page in the subtitle, which is where a reader who needs to match it will look.
 */
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
 * A rate as a count of calls per hundred, in words.
 *
 * <p>"Four calls in every hundred" is a thing a reader can picture; "4.02%" is a thing they have to
 * convert first. Words only up to twelve, because past that the numeral reads faster than the word,
 * and never for a rate below one in a hundred — "no calls in every hundred" would be a lie about a
 * tool that does fail, just rarely. That case gets the sub-one phrasing instead.
 */
export function perHundred(r: number, noun?: string): string {
  const what = noun ? `${noun} call` : "call";
  const n = r * 100;
  if (n < 1) return `fewer than one ${what} in every hundred`;
  const rounded = Math.round(n);
  const word = rounded < NUMBER_WORDS.length ? NUMBER_WORDS[rounded] : String(rounded);
  return `${word} ${what}${rounded === 1 ? "" : "s"} in every hundred`;
}

/** Sentence-cases a clause that starts with a number word. */
function upperFirst(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * A linear axis wide enough for both bars, ending on a round number.
 *
 * <p>Per finding rather than shared across findings: these rates run from 0.3% to 8%, and one axis
 * covering all of them would draw the small ones as slivers indistinguishable from each other. The
 * ladder is the set of maxima that divide into five readable ticks.
 */
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

/**
 * The two windows as a share of their own calls.
 *
 * <p>Both bars start at zero because both are proportions of the same thing, and a bar chart whose
 * baseline is not zero is a bar chart that lies about ratios. The reference bar is neutral and the
 * flagged bar carries the direction, so the colour says which way this went without a legend.
 */
export function RateChart({ rate }: { rate: Rate }) {
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
      aria-label={`Failure rate: ${formatRate(rate.refRate)} before, ${formatRate(rate.curRate)} after`}
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

      {/* Pin 1 is always the reading that fired the finding, so it sits on the flagged bar whichever
          side of the axis that lands on. */}
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

/** One numbered observation, keyed to a pin on the chart above. */
function Pin({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
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

/**
 * The two readings worth naming, in the order the argument is made: what it is now, then what it was.
 *
 * <p>Pin 1 is always the flagged window, because that is the number that fired the finding and the
 * one a reader is here for. Pin 2 is the reference, and it carries the sample size — a rate over 500
 * calls and a rate over 13,000 are different kinds of claim, and the denominator is the only thing on
 * this page that says which one this is.
 */
export function RatePins({ rate }: { rate: Rate }) {
  const tool = toolWords(rate.bucketKey);
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleDateString(undefined, DAY_MONTH) : null;
  const rose = rate.curRate > rate.refRate;
  const points = Math.abs(rate.deltaPp);

  return (
    <div className="flex flex-col rounded-card border border-border bg-surface mt-2.75">
      <Pin n={1} title={`${upperFirst(perHundred(rate.curRate, tool))} fail now`}>
        {rate.failuresCur.toLocaleString()} failure{rate.failuresCur === 1 ? "" : "s"} across the{" "}
        {rate.nCur.toLocaleString()} calls{since ? ` since ${since}` : ""}. This is the number that fired
        the finding.
      </Pin>
      <Pin n={2} title={`It was ${perHundred(rate.refRate)}`}>
        {formatRate(rate.refRate)} across the {rate.nRef.toLocaleString()} calls this classifier was fitted
        on, so the gap is {points.toFixed(2)} percentage point{points === 1 ? "" : "s"}{" "}
        {rose ? "wider" : "narrower"} than it was.
        {/* How strongly this fired is the detector's own statistic against its per-tool threshold, and
            neither is on the wire. The absolute failure count is the nearest honest stand-in: a rate
            resting on a dozen failures moves as far on one bad afternoon as on a real regression,
            however many calls sit under it. */}
        {rate.failuresCur < FEW_FAILURES && (
          <>
            {" "}
            The whole finding rests on {rate.failuresCur} of them, so one bad afternoon would move this
            as much as a real regression would. Treat it as a lead rather than a result.
          </>
        )}
      </Pin>
    </div>
  );
}
