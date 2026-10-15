// SPDX-License-Identifier: Apache-2.0
// A finding's evidence is two windows, not a time series, so these draw before/after rather than a line
// implying a trajectory nobody measured. The aligned "% change" column carries the argument.
import type { components } from "../../api/generated/schema";
import { cn } from "../../ui";

type Pair = components["schemas"]["Pair"];
type PatternShift = components["schemas"]["PatternShift"];

/** Fluid tracks: these render two to a row, where fixed value columns overflowed. */
const PAIR_COLS = "minmax(0, 1.6fr) minmax(48px, 0.8fr) minmax(48px, 0.8fr) minmax(46px, 0.7fr)";

/**
 * "Appeared" matters most: cache tokens go from absent to present when someone introduces caching, a
 * stronger signal than any percentage, and a divide-by-zero guard would hide it.
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

export function compact(n: number | null | undefined): string {
  if (n == null) return "–";
  const abs = Math.abs(n);
  if (abs >= 1000) return n.toLocaleString(undefined, { maximumFractionDigits: 0 });
  if (abs >= 1) return n.toLocaleString(undefined, { maximumFractionDigits: 2 });
  if (abs === 0) return "0";
  return n.toPrecision(2);
}

export function prettyKey(key: string): string {
  return key.replace(/^tok_/, "").replace(/_/g, " ");
}

export type EmphasisTone = "negative" | "positive";

function emphasisTextClass(tone?: EmphasisTone): string {
  return tone === "positive" ? "text-success" : "text-error";
}

export function PairBlock({
  title,
  caption,
  pairs,
  emphasiseKey,
  emphasiseTone,
}: {
  title: string;
  caption?: string;
  pairs: Pair[];
  emphasiseKey?: string;
  emphasiseTone?: EmphasisTone;
}) {
  const rows = pairs.filter((p) => p.then != null || p.now != null);
  if (rows.length === 0) return null;

  return (
    <section className="mt-6">
      <div className="flex items-baseline gap-3 mb-1">
        <h2 className="font-mono text-label uppercase text-muted">{title}</h2>
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
 * No before-and-after: the detector's reference is a fitted rate with no per-signature breakdown, so a
 * `ref` column would read 0 on every signature and claim each failure is new.
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
