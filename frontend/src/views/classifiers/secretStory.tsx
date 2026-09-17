// SPDX-License-Identifier: Apache-2.0
/*
 * A secret leak told as a story: which masked key leaked, into which outputs, over what stretch of
 * time — and whether a copy of it is still sitting in trace storage unredacted.
 *
 * <h2>Why this is not {@link ./rateStory}</h2>
 * A secret leak has no reference rate: it never "was normal at some level and moved". One leak is
 * already the whole claim, so there is nothing to plot as before-and-after. What a reader needs
 * instead is WHEN each leak happened and WHICH key it was — a timeline with one lane per key, not a
 * bar chart with nothing to compare against.
 *
 * <h2>A masked key, never the credential</h2>
 * Every string this file prints is already masked server-side (`SecretLeakKeyView.masked`,
 * `SecretLeakLeakView.masked` — provider prefix, then `…`, then the last four characters). Nothing
 * here ever sees the raw match, so there is no redaction logic to get wrong on this side of the wire.
 */
import { useMemo } from "react";
import type { components } from "../../api/generated/schema";
import type { BehaviorFinding } from "../../api/types";
import { PageHeader } from "../../ui";
import { RunTriageButton, detectorLabel, triageState } from "./shared";
import { Pin, PinList } from "./rateStory";

type SecretLeakDetail = components["schemas"]["SecretLeakDetail"];
type SecretLeakLeak = components["schemas"]["SecretLeakLeakView"];

/** How a leak was left in trace storage. `null`/`"unknown"` are the same forward-only gap (decision
 *  #2): a leak recorded before this classifier started stamping a masked key carries no verdict. */
function storedTone(stored: string): "raw" | "redacted" | "unknown" {
  return stored === "raw" ? "raw" : stored === "redacted" ? "redacted" : "unknown";
}

function storedColour(tone: "raw" | "redacted" | "unknown"): string {
  switch (tone) {
    case "raw":
      return "var(--color-error)";
    case "redacted":
      return "var(--color-fg-secondary)";
    default:
      return "var(--color-subtle)";
  }
}

const DAY_MONTH: Intl.DateTimeFormatOptions = { day: "numeric", month: "long" };
const DAY_MONTH_YEAR: Intl.DateTimeFormatOptions = { day: "numeric", month: "long", year: "numeric" };
const CLOCK: Intl.DateTimeFormatOptions = { hour: "2-digit", minute: "2-digit", hour12: false };

function isSameDay(a: Date, b: Date): boolean {
  return a.toDateString() === b.toDateString();
}

/** "10:02 today" beside a recent leak, "14 Sep, 10:02" once it isn't the day being read. */
function whenShort(iso: string, now: Date): string {
  const d = new Date(iso);
  const time = d.toLocaleTimeString(undefined, CLOCK);
  return isSameDay(d, now) ? `${time} today` : `${d.toLocaleDateString(undefined, DAY_MONTH)}, ${time}`;
}

/** "14 minutes ago" / "3 hours ago" / "2 days ago" — spelled out, because this is a headline, not a
 *  table cell abbreviation. */
function relativeWords(iso: string, now: Date): string {
  const ms = now.getTime() - new Date(iso).getTime();
  if (!Number.isFinite(ms) || ms < 60_000) return "moments ago";
  const mins = Math.round(ms / 60_000);
  if (mins < 60) return `${mins} minute${mins === 1 ? "" : "s"} ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? "" : "s"} ago`;
}

/** A leak inside this window still reads as "still leaking"; past it, the finding reads as history
 *  even though it has not formally closed. A day is the same grain the timeline itself ticks in. */
const STILL_LEAKING_WITHIN_MS = 24 * 60 * 60 * 1000;

function latestLeakOf(leaks: SecretLeakLeak[]): SecretLeakLeak | null {
  if (leaks.length === 0) return null;
  return leaks.reduce((a, b) => (new Date(b.at ?? 0).getTime() > new Date(a.at ?? 0).getTime() ? b : a));
}

/**
 * Where to send a reader who clicks a trace id. The finding page and the case page resolve relative
 * links differently (see `FindingPage.tsx`'s note on why `..` cannot be reused blind), so this is
 * handed in rather than hard-coded.
 */
export type TraceLinker = (traceId: string, spanId?: string | null) => string;

const LINK_CLASS = "font-mono text-link hover:text-link-hover transition-colors";

/**
 * The leak timeline: one lane per masked key, one dot per leaking output, coloured by whether that
 * copy sits in trace storage unredacted. The bordered card also carries the legend, so a reader never
 * has to look elsewhere to read a dot's colour.
 */
export function LeakTimeline({ secretLeak }: { secretLeak: SecretLeakDetail }) {
  const now = useMemo(() => new Date(), []);
  const { keys, leaks } = secretLeak;

  const PX0 = 96;
  const PX1 = 1024;
  const PY0 = 22;
  const LANE_GAP = 40;
  const height = Math.max(PY0 * 2 + LANE_GAP * Math.max(keys.length, 1), 130);

  const times = [
    ...leaks.map((l) => l.at),
    ...keys.map((k) => k.lastAt),
    secretLeak.firstAt,
  ].filter((t): t is string => t != null);
  const earliest = times.length > 0 ? Math.min(...times.map((t) => new Date(t).getTime())) : now.getTime();
  const start = new Date(earliest);
  start.setHours(0, 0, 0, 0);
  const domainStart = start.getTime();
  const span = Math.max(now.getTime() - domainStart, 24 * 60 * 60 * 1000);
  const domainEnd = domainStart + span * 1.04;
  const x = (iso: string) => PX0 + ((new Date(iso).getTime() - domainStart) / (domainEnd - domainStart)) * (PX1 - PX0);
  const nowX = PX0 + ((now.getTime() - domainStart) / (domainEnd - domainStart)) * (PX1 - PX0);

  const dayCount = Math.max(1, Math.round(span / (24 * 60 * 60 * 1000)));
  const dayStep = Math.max(1, Math.ceil(dayCount / 8));
  const dayTicks = Array.from({ length: Math.floor(dayCount / dayStep) + 1 }, (_, i) => {
    const t = domainStart + i * dayStep * 24 * 60 * 60 * 1000;
    return t <= now.getTime() ? t : null;
  }).filter((t): t is number => t != null);

  const laneY = (i: number) => PY0 + i * LANE_GAP + LANE_GAP / 2;

  const showUnknownLegend = leaks.some((l) => storedTone(l.stored) === "unknown");

  return (
    <div className="rounded-card border border-border bg-surface pt-4.5 px-5 pb-3">
      <svg
        viewBox={`0 0 1120 ${height}`}
        style={{ width: "100%", height: "auto", display: "block" }}
        role="img"
        aria-label={`When each of ${keys.length} masked keys leaked, over the last ${dayCount} days`}
      >
        {dayTicks.map((t) => (
          <line
            key={t}
            x1={PX0 + ((t - domainStart) / (domainEnd - domainStart)) * (PX1 - PX0)}
            y1={8}
            x2={PX0 + ((t - domainStart) / (domainEnd - domainStart)) * (PX1 - PX0)}
            y2={height - 24}
            stroke="var(--color-chart-grid)"
            strokeWidth={1}
          />
        ))}
        <line
          x1={nowX}
          y1={0}
          x2={nowX}
          y2={height - 24}
          stroke="var(--color-subtle)"
          strokeWidth={1}
          strokeDasharray="2 3"
        />
        <text x={nowX} y={10} textAnchor="middle" fontSize={11} fill="var(--color-muted)" fontFamily="var(--font-mono)">
          now
        </text>

        {keys.map((k, i) => (
          <text
            key={k.masked}
            x={0}
            y={laneY(i) + 4}
            fontSize={11}
            fill="var(--color-muted)"
            fontFamily="var(--font-mono)"
          >
            {k.masked}
          </text>
        ))}

        {leaks.map((l, i) => {
          const lane = keys.findIndex((k) => k.masked === l.masked);
          if (lane < 0 || l.at == null) return null;
          const tone = storedTone(l.stored);
          return (
            <circle
              key={`${l.traceId}-${l.spanId ?? ""}-${i}`}
              cx={x(l.at)}
              cy={laneY(lane)}
              r={5}
              fill={storedColour(tone)}
              stroke="var(--color-surface)"
              strokeWidth={2}
            >
              <title>
                {`${l.masked} · ${new Date(l.at).toLocaleString()} · ${
                  tone === "raw" ? "stored unredacted" : tone === "redacted" ? "redacted at ingest" : "storage unknown"
                }`}
              </title>
            </circle>
          );
        })}

        <line x1={PX0} y1={height - 24} x2={PX1} y2={height - 24} stroke="var(--color-chart-grid)" strokeWidth={1} />
        {dayTicks.map((t) => (
          <text
            key={t}
            x={PX0 + ((t - domainStart) / (domainEnd - domainStart)) * (PX1 - PX0)}
            y={height - 6}
            textAnchor="middle"
            fontSize={10.5}
            fill="var(--color-subtle)"
            fontFamily="var(--font-mono)"
          >
            {new Date(t).toLocaleDateString(undefined, DAY_MONTH)}
          </text>
        ))}
      </svg>
      <div className="flex flex-wrap gap-4.5 text-muted mt-1.5 text-small" style={{ paddingLeft: PX0 }}>
        <LegendDot colour="var(--color-fg-secondary)" label="Redacted at ingest" />
        <LegendDot colour="var(--color-error)" label="Stored unredacted" />
        {showUnknownLegend && <LegendDot colour="var(--color-subtle)" label="Storage unknown" />}
      </div>
    </div>
  );
}

function LegendDot({ colour, label }: { colour: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="inline-block rounded-pill" style={{ width: 8, height: 8, background: colour }} />
      {label}
    </span>
  );
}

/**
 * The three numbered observations a leak timeline is read by: is it still leaking, when it started,
 * and whether any of it sits in storage unredacted. Third pin only renders when it has something to
 * say — most leaks are fully redacted, and a pin that always fires to say "none" is noise.
 */
export function LeakPins({ secretLeak, linkToTrace }: { secretLeak: SecretLeakDetail; linkToTrace: TraceLinker }) {
  const now = useMemo(() => new Date(), []);
  const latest = latestLeakOf(secretLeak.leaks);
  const stillLeaking =
    secretLeak.lastAt != null && now.getTime() - new Date(secretLeak.lastAt).getTime() < STILL_LEAKING_WITHIN_MS;

  const rawLeaks = secretLeak.leaks.filter((l) => storedTone(l.stored) === "raw");

  return (
    <PinList>
      <Pin
        n={1}
        title={
          stillLeaking && secretLeak.lastAt
            ? `Still leaking. The latest was ${relativeWords(secretLeak.lastAt, now)}`
            : secretLeak.lastAt
              ? `Last leaked ${new Date(secretLeak.lastAt).toLocaleDateString(undefined, DAY_MONTH_YEAR)}`
              : "No leak on record"
        }
      >
        {latest ? (
          <>
            Key <span className="font-mono">{latest.masked}</span> at {whenShort(latest.at ?? secretLeak.lastAt ?? "", now)}
            {latest.traceId && (
              <>
                , in trace{" "}
                <a className={LINK_CLASS} href={linkToTrace(latest.traceId, latest.spanId)}>
                  {latest.traceId.slice(0, 8)}…
                </a>
              </>
            )}
            . {stillLeaking ? "This is the leak that keeps the finding open." : "The spans behind older leaks may have aged out of retention."}
          </>
        ) : (
          "The spans behind this finding have aged out of retention."
        )}
      </Pin>
      <Pin
        n={2}
        title={
          secretLeak.firstAt
            ? `It started on ${new Date(secretLeak.firstAt).toLocaleDateString(undefined, DAY_MONTH)} at ${new Date(
                secretLeak.firstAt,
              ).toLocaleTimeString(undefined, CLOCK)}`
            : "The start of this leak is not on record"
        }
      >
        No earlier output matched this rule.
      </Pin>
      {rawLeaks.length > 0 && (
        <Pin
          n={3}
          title={`${rawLeaks.length} of the ${secretLeak.leakCount} ${secretLeak.leakCount === 1 ? "is" : "are"} stored unredacted`}
        >
          {rawLeaks.length === 1 ? "That copy" : "Those copies"} sit{rawLeaks.length === 1 ? "s" : ""} in trace storage
          until retention removes {rawLeaks.length === 1 ? "it" : "them"}.
        </Pin>
      )}
    </PinList>
  );
}

/**
 * The finding's own header. Same anatomy as {@link ./rateStory.RateChart}'s header
 * (`FindingPage.tsx`'s `RateHeader`): breadcrumb, detector + call site kicker, a title naming the
 * claim, a subtitle carrying the numbers, and the one action available before triage.
 *
 * <p>A high-confidence secret leak opens its case straight from detection (decision: no triage
 * gate) — "Run triage" here is Layer-2 analysis, the same escalation every other finding offers, not
 * the case-opening decision.
 */
export function SecretHeader({
  secretLeak,
  finding,
  basePath,
  busy,
  onAnalyze,
}: {
  secretLeak: SecretLeakDetail;
  finding: BehaviorFinding;
  basePath: string;
  busy: boolean;
  onAnalyze: () => void;
}) {
  const site = finding.callSiteId ?? "its call site";
  return (
    <PageHeader
      breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
      kicker={
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>
          {finding.callSiteId && <span className="text-muted">{finding.callSiteId}</span>}
        </span>
      }
      title={
        <span>
          <span className="font-mono">{secretLeak.rule}</span> in <span className="font-mono">{site}</span> output
        </span>
      }
      subtitle={
        <span title={finding.causeKey}>
          Rule <span className="font-mono">{secretLeak.rule}</span> · {secretLeak.confidence} confidence ·{" "}
          {secretLeak.leakCount.toLocaleString()} leak{secretLeak.leakCount === 1 ? "" : "s"} across{" "}
          {secretLeak.traceCount.toLocaleString()} trace{secretLeak.traceCount === 1 ? "" : "s"}
        </span>
      }
      actions={
        finding.triageStatus === "done" ? (
          <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
            {triageState(finding).label}
          </span>
        ) : (
          <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />
        )
      }
    />
  );
}
