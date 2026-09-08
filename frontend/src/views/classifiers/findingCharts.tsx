// SPDX-License-Identifier: Apache-2.0
/*
 * The figures a finding is made of.
 *
 * <h2>Why these are bespoke rather than the shared chart primitives</h2>
 * `TrendChart` and `StackedBarChart` both draw a series over time, and a finding has no time series:
 * its evidence is TWO WINDOWS (the reference and the one that just closed) with a handful of paired
 * readings between them. The only honest picture of that is a before/after comparison, so these draw
 * one, rather than interpolating two points into a line that would imply a trajectory nobody measured.
 *
 * <h2>The comparison is the argument</h2>
 * A metric-drift finding claims the agent changed rather than the traffic. It supports that by showing
 * what users asked for holding still while what the agent produced moved. Rendering the measure's pair
 * beside the workload's pairs, on the same "% change" scale, IS that argument, which is why the change
 * column is the thing aligned and emphasised rather than the raw values.
 */
import type { components } from "../../api/generated/schema";
import { cn } from "../../ui";

type Pair = components["schemas"]["Pair"];
type PatternShift = components["schemas"]["PatternShift"];

/**
 * The column track for a paired-reading row: reading, before, after, change. Every track is fluid
 * because these blocks render two to a row inside a grid, where fixed value columns overflowed.
 */
const PAIR_COLS = "minmax(0, 1.6fr) minmax(48px, 0.8fr) minmax(48px, 0.8fr) minmax(46px, 0.7fr)";

/**
 * What happened to a paired reading. A percent change is only one of the answers, and the others are
 * the interesting ones on this surface.
 *
 * <p>"Appeared" is the case that matters most: cache-read and cache-write tokens go from absent to
 * present exactly when someone introduces caching, and a reading that went from nothing to something is
 * a much stronger statement than any percentage. Collapsing it into "not measured", which is what a
 * bare divide-by-zero guard does, hides the single clearest signal the block carries.
 */
export type Change =
  | { kind: "pct"; value: number }
  | { kind: "appeared" }
  | { kind: "stopped" }
  | { kind: "unmeasured" };

export function changeOf(pair: Pair): Change {
  const { then, now } = pair;
  const hadThen = then != null && then !== 0;
  const hasNow = now != null && now !== 0;
  if (!hadThen && !hasNow) return { kind: "unmeasured" };
  if (!hadThen) return { kind: "appeared" };
  if (!hasNow) return { kind: "stopped" };
  return { kind: "pct", value: ((now! - then!) / Math.abs(then!)) * 100 };
}

function changeLabel(change: Change): string {
  switch (change.kind) {
    case "pct":
      return `${change.value >= 0 ? "+" : ""}${change.value.toFixed(0)}%`;
    case "appeared":
      return "new";
    case "stopped":
      return "gone";
    default:
      return "–";
  }
}

/**
 * A number at the precision a reader can hold. Costs run to four decimals and durations to five digits,
 * and neither is worth printing in full on a comparison whose point is the size of the gap.
 */
export function compact(n: number | null | undefined): string {
  if (n == null) return "–";
  const abs = Math.abs(n);
  if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  if (abs >= 1) return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  if (abs === 0) return "0";
  return n.toPrecision(2);
}

/** `tok_input_p50` → `input p50`. The block already says these are tokens. */
export function prettyKey(key: string): string {
  return key.replace(/^tok_/, "").replace(/_/g, " ");
}

/**
 * What the emphasised row means, when the caller knows.
 *
 * <p>A distribution shift knows which way it went, so its driving row is drawn in the same red or
 * green the chart above it uses — one colour vocabulary per page. A caller that does not pass a tone
 * keeps the older neutral-warning treatment, which is what a rate shift still wants: `emphasiseKey`
 * there marks the row the finding is ABOUT, not a row that moved in a knowable direction.
 */
export type EmphasisTone = "negative" | "positive";

function emphasisTextClass(tone?: EmphasisTone): string {
  if (tone === "positive") return "text-success";
  if (tone === "negative") return "text-error";
  return "text-warning";
}

/**
 * A block of paired readings: the measure's own quantiles, the workload, or the token decomposition.
 *
 * <p>Every row prints then, now and the percent change. There used to be a diverging bar beside that
 * percentage, scaled across the block; it was drawing the number that already sat next to it, in a
 * column narrow enough that the drawing carried less than the digits did.
 */
export function PairBlock({
  title,
  caption,
  pairs,
  emphasiseKey,
  emphasiseTone,
  unit,
}: {
  title: string;
  caption?: string;
  pairs: Pair[];
  /** The row that carries the finding; everything else neutral. */
  emphasiseKey?: string;
  /** Which way that row went, when the caller knows. See {@link EmphasisTone}. */
  emphasiseTone?: EmphasisTone;
  unit?: string;
}) {
  const rows = pairs.filter((p) => p.then != null || p.now != null);
  if (rows.length === 0) return null;

  return (
    <section className="mt-6">
      <div className="flex items-baseline gap-3 mb-1">
        <h2 className="font-mono text-label uppercase text-muted">{title}</h2>
        {unit && <span className="text-subtle text-label">{unit}</span>}
      </div>
      {caption && (
        <p className="text-subtle mt-0 mx-0 mb-2.5 text-small" style={{ maxWidth: 640 }}>
          {caption}
        </p>
      )}
      <div className="rounded-card border border-border overflow-hidden">
        <div className="flex flex-col gap-px bg-border">
          <div
            className="grid items-center bg-surface text-subtle gap-3 py-2 px-3.5 text-label"
            style={{ gridTemplateColumns: PAIR_COLS }}
          >
            <span>Reading</span>
            <span style={{ textAlign: "right" }}>Before</span>
            <span style={{ textAlign: "right" }}>After</span>
            <span style={{ textAlign: "right" }}>Change</span>
          </div>
          {rows.map((p) => {
            const change = changeOf(p);
            const emphasise = p.key === emphasiseKey;
            return (
              <div
                key={p.key}
                className="grid items-center bg-surface gap-3 py-2.25 px-3.5 text-small"
                style={{ gridTemplateColumns: PAIR_COLS }}
              >
                <span className={cn("truncate", emphasise ? "text-fg" : "text-muted")}>{prettyKey(p.key)}</span>
                <span className="font-mono text-subtle" style={{ textAlign: "right" }}>
                  {compact(p.then)}
                </span>
                <span className="font-mono text-muted" style={{ textAlign: "right" }}>
                  {compact(p.now)}
                </span>
                <span
                  className={cn("font-mono", emphasise ? emphasisTextClass(emphasiseTone) : "text-subtle")}
                  style={{ textAlign: "right" }}
                >
                  {changeLabel(change)}
                </span>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

/**
 * The failure-signature breakdown of a rate shift, ranked by how much each signature CHANGED.
 *
 * <p>This is the one chart that answers the question a rate shift actually poses: a rise spread evenly
 * across signatures that were always there is usually the traffic moving, while one signature going from
 * rare to common is the tool breaking. Two bars per row, on a shared scale, make that difference visible
 * without reading a single count.
 */
/**
 * The failure signatures seen in the flagged window.
 *
 * <p><b>No before-and-after, because there was never one to show.</b> This used to render each
 * signature as `ref → cur` with two bars, and the `ref` side was `0` on every signature of every
 * tool-error finding ever produced — not because the failure was new, but because the number is
 * built by ranking against `new ToolErrorRate()`, an empty reference passed in by construction. The
 * detector's reference is a FITTED RATE (502 calls, 27 failures) with no per-signature breakdown
 * retained, so there is nothing to rank against and nothing to put in that column.
 *
 * <p>`0 → 3` therefore read as "a brand-new failure appeared" on a finding whose failures may well
 * have been the same signature all along at a higher rate — the exact opposite reading. So the
 * direction is stated once, from the rate that IS measured, and the signatures are listed as what
 * they are: what the failures in this window look like.
 */
export function PatternBlock({
  patterns,
  truncated,
  elevated,
}: {
  patterns: PatternShift[];
  truncated: boolean;
  elevated: boolean;
}) {
  if (patterns.length === 0) return null;

  return (
    <section className="mt-6">
      <h2 className="font-mono text-label uppercase text-muted mb-1">
        Failure patterns
      </h2>
      <p className="text-subtle mt-0 mx-0 mb-2.5 text-small" style={{ maxWidth: 640 }}>
        <span className={elevated ? "text-error" : "text-fg"}>
          {elevated ? "Elevated tool errors." : "Lower tool errors."}
        </span>{" "}
        These are the failure signatures in the flagged window. How often each one occurred before is
        not recorded: this classifier compares against a fitted rate rather than a stretch of traffic it
        kept, so there is no per-signature before-and-after to show.
      </p>
      <div className="rounded-card border border-border overflow-hidden">
        <div className="flex flex-col gap-px bg-border">
          {patterns.map((p, i) => (
            <div key={`${p.signature}-${i}`} className="bg-surface py-2.5 px-3.5">
              <span className="min-w-0 flex-1 truncate font-mono text-muted text-small">
                {p.signature || "(no signature)"}
              </span>
            </div>
          ))}
        </div>
      </div>
      {truncated && (
        <p className="text-subtle mt-2 text-small">
          Truncated: the ranked head of a longer list, not the whole of it.
        </p>
      )}
    </section>
  );
}
