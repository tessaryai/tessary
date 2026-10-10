// SPDX-License-Identifier: Apache-2.0
/*
 * The rules a chart card on the Classifiers page draws by, apart from React.
 *
 * The wire carries raw units (rates as fractions, durations in milliseconds, cost in dollars) and times as UTC
 * instants, so every word and number a reader sees is made here. A rate's change is in percentage points, never a percent
 * change (see rateStory.tsx), and a change is "worse" when the value rose: every measure charted here is
 * lower-is-better.
 */
import type {
  ChartCallSiteOption,
  ChartCard,
  ChartCaseSpan,
  ChartChip,
  ChartPoint,
  ChartToolOption,
  ClassifierMenuItem,
} from "../../api/types";
import { PROVIDER_PAUSES } from "./shared";

/** The rows a card's case strip has. When more cases ran than fit, the last row says "+N more" instead. */
export const MAX_LANES = 3;

const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

export type AxisUnit = "percent" | "seconds" | "usd" | "count";

/** The unit a card's values are drawn in on its axis. */
export function axisUnitOf(card: ChartCard): AxisUnit {
  if (card.kind === "count") return "count";
  if (card.unit === "ms") return "seconds";
  if (card.unit === "usd") return "usd";
  return "percent";
}

/** A wire value in its card's axis unit: a rate fraction as percent, milliseconds as seconds. */
export function toAxis(unit: AxisUnit, v: number): number {
  if (unit === "percent") return v * 100;
  if (unit === "seconds") return v / 1000;
  return v;
}

function decimalsOf(step: number): number {
  return Math.max(0, -Math.floor(Math.log10(step) + 1e-9));
}

function tickLabel(unit: AxisUnit, v: number, decimals: number): string {
  if (unit === "count") return String(Math.round(v));
  if (v === 0) return unit === "percent" ? "0%" : unit === "seconds" ? "0 s" : "$0";
  const n = v.toFixed(decimals);
  return unit === "percent" ? `${n}%` : unit === "seconds" ? `${n} s` : `$${n}`;
}

/**
 * A linear axis from zero to the first round step at or above `max`, in about four steps of 1, 2 or 5 times a power
 * of ten. A count steps in whole numbers, and an axis over nothing still spans one step so it has a height.
 */
export function yAxis(max: number, unit: AxisUnit): { top: number; ticks: { value: number; label: string }[] } {
  const minStep = unit === "count" ? 1 : 0;
  const span = max > 0 ? max : 1;
  const raw = span / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  let step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw * (1 - 1e-9)) ?? 10 * pow;
  if (step < minStep) step = minStep;
  const steps = Math.max(1, Math.ceil(span / step - 1e-9));
  const decimals = decimalsOf(step);
  const ticks = Array.from({ length: steps + 1 }, (_, i) => {
    const value = Number((i * step).toFixed(decimals + 2));
    return { value, label: tickLabel(unit, value, decimals) };
  });
  return { top: steps * step, ticks };
}

/** `2026-10-08` or `2026-10-06T23:30:00Z` as the UTC day it falls on: "Oct 6". */
export function utcDay(iso: string): string {
  const at = iso.length === 10 ? `${iso}T00:00:00Z` : iso;
  return new Date(at).toLocaleString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
}

/**
 * A card's time axis in epoch milliseconds: from 00:00 UTC of the range's first day to the end of the current hour.
 * It stretches to the last rate or range point when the server's clock runs ahead of the browser's. A count card's
 * open bucket can end hours past now, so its bar is cut at the axis end instead.
 */
export function timeAxis(card: ChartCard, fromDay: string, now: number): { from: number; to: number } {
  const from = Date.parse(`${fromDay}T00:00:00Z`);
  let to = Math.floor(now / HOUR_MS) * HOUR_MS + HOUR_MS;
  if (card.kind !== "count") for (const p of card.points) to = Math.max(to, Date.parse(p.end_at));
  return { from, to };
}

/** The day ticks of a range at 00:00 UTC, counted back from the last day: each day for 7 days, each week for 28. */
export function xTicks(fromDay: string, toDay: string): { at: number; label: string }[] {
  const from = Date.parse(`${fromDay}T00:00:00Z`);
  const last = Date.parse(`${toDay}T00:00:00Z`);
  const gap = (last - from) / DAY_MS < 7 ? DAY_MS : 7 * DAY_MS;
  const ticks: { at: number; label: string }[] = [];
  for (let at = last; at >= from; at -= gap) ticks.unshift({ at, label: utcDay(new Date(at).toISOString()) });
  return ticks;
}

/** One rate or range point as drawn: a level held from `start` to `end`, in epoch milliseconds. */
export type Step = { start: number; end: number; v: number; open: boolean };

/**
 * The SVG paths of a step series: each step a horizontal segment, joined to the one before by a vertical at its start
 * only when it starts within an hour of where that one ended, else left apart. The open step, the last one, goes in
 * `dashed` with its connector so it reads as still filling.
 */
export function stepPaths(steps: Step[], x: (t: number) => number, y: (v: number) => number): { solid: string; dashed: string } {
  let solid = "";
  let dashed = "";
  const at = (t: number, v: number) => `${x(t).toFixed(1)} ${y(v).toFixed(1)}`;
  steps.forEach((s, i) => {
    const prev = steps[i - 1];
    const joined = prev != null && s.start - prev.end <= HOUR_MS;
    let d = "";
    if (joined && s.open) d += `M${at(prev.end, prev.v)}`;
    d += joined ? `H${x(s.start).toFixed(1)}V${y(s.v).toFixed(1)}` : `M${at(s.start, s.v)}`;
    d += `H${x(s.end).toFixed(1)}`;
    if (s.open) dashed += d;
    else solid += d;
  });
  return { solid, dashed };
}

/** A rate fraction as a percent with one decimal; a non-zero rate too small for that reads "<0.1%". */
export function percent(f: number): string {
  const p = f * 100;
  if (p > 0 && p < 0.05) return "<0.1%";
  return `${p.toFixed(1)}%`;
}

function secondsDecimals(ms: number): number {
  return Math.abs(ms) >= 1000 ? 1 : 2;
}

function usdDecimals(v: number): number {
  const a = Math.abs(v);
  return a >= 1 ? 2 : a >= 0.01 ? 3 : 4;
}

/** Milliseconds as seconds: one decimal from a second up, two below it. */
export function seconds(ms: number, decimals = secondsDecimals(ms)): string {
  return `${(ms / 1000).toFixed(decimals)} s`;
}

/** Dollars at the precision a per-turn cost needs: cents from a dollar up, finer below. */
export function dollars(v: number, decimals = usdDecimals(v)): string {
  return `$${v.toFixed(decimals)}`;
}

/** A card's own value (a rate fraction, milliseconds or dollars) as the reader sees it. */
export function formatValue(card: ChartCard, v: number): string {
  if (card.kind === "count") return Math.round(v).toLocaleString("en-US");
  if (card.unit === "ms") return seconds(v);
  if (card.unit === "usd") return dollars(v);
  return percent(v);
}

export type DeltaTone = "worse" | "better" | "same";

const MINUS = "−";

/** The change against the baseline, at the precision the headline is printed in. Null while there is no baseline. */
function deltaOf(card: ChartCard): { text: string; tone: DeltaTone } | null {
  const { value, delta } = card.headline;
  if (card.learning || delta == null || value == null) return null;
  let magnitude: string;
  let zero: string;
  if (card.unit === "ms") {
    const d = secondsDecimals(value);
    magnitude = seconds(Math.abs(delta), d);
    zero = seconds(0, d);
  } else if (card.unit === "usd") {
    const d = usdDecimals(value);
    magnitude = dollars(Math.abs(delta), d);
    zero = dollars(0, d);
  } else {
    magnitude = `${(Math.abs(delta) * 100).toFixed(1)} pp`;
    zero = "0.0 pp";
  }
  if (magnitude === zero) return { text: "No change", tone: "same" };
  return delta > 0 ? { text: `+${magnitude}`, tone: "worse" } : { text: `${MINUS}${magnitude}`, tone: "better" };
}

/** What a rate classifier's flag means, its population, and the axis title, by classifier key. */
const RATE_WORDS: Record<string, { verb: string; noun: string; title: string }> = {
  frustration: { verb: "flagged", noun: "conversations", title: "% of conversations flagged" },
  groundedness: { verb: "unsupported", noun: "answers", title: "% of answers unsupported" },
  malformed_output: { verb: "failed the schema", noun: "outputs", title: "% of outputs that fail the schema" },
  tool_error: { verb: "failed", noun: "calls", title: "% of calls failed" },
};
const RATE_DEFAULT = { verb: "flagged", noun: "checks", title: "% flagged" };

/** A range card's measure: the slow-end word, the population, and the axis title. */
const RANGE_WORDS: Record<string, { high: string; one: string; noun: string; label: string; title: string }> = {
  turn_duration: { high: "Slow", one: "turn", noun: "turns", label: "slow turns (p95)", title: "Seconds per turn" },
  tool_duration: { high: "Slow", one: "call", noun: "calls", label: "slow calls (p95)", title: "Seconds per call" },
  cost: { high: "Expensive", one: "turn", noun: "turns", label: "expensive turns (p95)", title: "Cost per turn, USD" },
};
const RANGE_DEFAULT = { high: "High", one: "sample", noun: "samples", label: "high end (p95)", title: "Per sample" };

const rateWords = (card: ChartCard) => RATE_WORDS[card.classifier_key] ?? RATE_DEFAULT;
const rangeWords = (card: ChartCard) => RANGE_WORDS[card.measure ?? ""] ?? RANGE_DEFAULT;

/** A stretch of time as words: per "6 hours", "in an hour", "that day". */
function lengthWords(ms: number): { per: string; within: string; that: string } {
  const hours = ms / HOUR_MS;
  if (hours === 1) return { per: "hour", within: "an hour", that: "hour" };
  if (hours === 24) return { per: "day", within: "a day", that: "day" };
  return { per: `${hours} hours`, within: `${hours} hours`, that: `${hours}-hour window` };
}

/** The width of a count card's buckets in milliseconds: every one is the same. */
function bucketMs(card: ChartCard): number | null {
  const p = card.points[0];
  return p ? Date.parse(p.end_at) - Date.parse(p.start_at) : null;
}

/** The y-axis title above a card's chart. */
export function axisTitle(card: ChartCard): string {
  if (card.kind === "count") {
    const ms = bucketMs(card);
    return ms ? `Detections per ${lengthWords(ms).per}` : "Detections";
  }
  if (card.kind === "range") return rangeWords(card).title;
  return rateWords(card).title;
}

/** The card's second row: the last 7 days' value, its change against the baseline, and what the value is. */
export function headlineOf(card: ChartCard): {
  value: string;
  delta: { text: string; tone: DeltaTone } | null;
  label: string;
} {
  const label =
    card.kind === "count"
      ? "detections, last 7 days"
      : card.kind === "range"
        ? `${rangeWords(card).label}, last 7 days`
        : `${rateWords(card).verb}, last 7 days`;
  const value = card.headline.value == null ? "–" : formatValue(card, card.headline.value);
  return { value, delta: deltaOf(card), label };
}

const isOpen = (s: ChartCaseSpan) => s.end_at == null;

/**
 * The cases a card's strip draws, in at most three rows: every case when three or fewer ran, else two and a
 * "+N more" row. The open ones come first and then the newest, drawn oldest first so they read left to right.
 * `more` is how many were left out.
 */
export function lanesOf(spans: ChartCaseSpan[]): { lanes: ChartCaseSpan[]; more: number } {
  const ranked = [...spans].sort(
    (a, b) => Number(isOpen(b)) - Number(isOpen(a)) || Date.parse(b.start_at) - Date.parse(a.start_at),
  );
  const fit = spans.length > MAX_LANES ? MAX_LANES - 1 : MAX_LANES;
  const lanes = ranked.slice(0, fit).sort((a, b) => Date.parse(a.start_at) - Date.parse(b.start_at));
  return { lanes, more: spans.length - lanes.length };
}

const RESOLUTION_WORDS: Record<string, string> = {
  recovered: "recovered",
  human: "closed by hand",
  absorbed: "merged",
};

/** How a closed case ended: its disposition when a person gave one, else how it was resolved. */
function howItEnded(span: ChartCaseSpan): string {
  if (span.disposition) return span.disposition.replace(/_/g, " ");
  return RESOLUTION_WORDS[span.resolution ?? ""] ?? "closed";
}

/** A case bar's hover text: its reference, its title, and when it ran. */
export function laneTooltip(span: ChartCaseSpan): string[] {
  const when =
    span.end_at == null
      ? `Open since ${utcDay(span.start_at)}`
      : `${utcDay(span.start_at)} to ${utcDay(span.end_at)}, ${howItEnded(span)}`;
  return [`Opens ${span.case_reference}`, span.case_title, when];
}

const plural = (n: number, one: string, many: string) => `${n.toLocaleString("en-US")} ${n === 1 ? one : many}`;

/**
 * The threshold of a count card's "N in a day opens a finding" line, and its words. A bar can carry the line only when
 * it is as wide as the arming window; otherwise the bright bars alone say which windows reached it.
 */
export function armingLine(card: ChartCard): { threshold: number; label: string } | null {
  const ms = bucketMs(card);
  if (!card.arming || ms !== card.arming.window_seconds * 1000) return null;
  return { threshold: card.arming.threshold, label: `${card.arming.threshold} in ${lengthWords(ms).within} opens a finding` };
}

/**
 * One bucket's bar on a count card. Its height is this call site's detections (total, not count), the number the
 * headline sums and the tooltip reads, so a user classifier's card never draws other call sites' detections. It is
 * bright when what the arming bar counts reached the threshold in the window holding it; for a user classifier that
 * count is project-wide, so a short bar can be the one that opened a finding.
 */
export function countBar(p: ChartPoint): { height: number; reached: boolean } {
  return { height: p.total ?? 0, reached: p.reached === true };
}

const hhmm = (ms: number) => new Date(ms).toISOString().slice(11, 16);

/** A point's stretch of UTC time: "Oct 7, 14:00-16:00 UTC", or both dates when it crosses midnight. */
export function spanLabel(startAt: string, endAt: string): string {
  const start = Date.parse(startAt);
  const end = Date.parse(endAt);
  const day = utcDay(startAt);
  if (utcDay(new Date(end - 1).toISOString()) === day) {
    return `${day}, ${hhmm(start)}-${end % DAY_MS === 0 ? "24:00" : hhmm(end)} UTC`;
  }
  return `${day}, ${hhmm(start)} to ${utcDay(endAt)}, ${hhmm(end)} UTC`;
}

/** The hover text for one point of a card: its time span first, then its numbers by card kind. */
export function pointTooltip(card: ChartCard, index: number): string[] {
  const p = card.points[index];
  const when = `${spanLabel(p.start_at, p.end_at)}${p.open ? ", still filling" : ""}`;
  if (card.kind === "count") {
    const total = p.total ?? 0;
    const count = p.count ?? total;
    const lines = [when, plural(total, "detection", "detections")];
    if (!card.arming || p.reached == null) return lines;
    if (count !== total) lines.push(`${count.toLocaleString("en-US")} counted toward a finding`);
    const { threshold, window_seconds } = card.arming;
    const that = lengthWords(window_seconds * 1000).that;
    lines.push(p.reached ? `Reached ${threshold} that ${that}, opened a finding` : `Below ${threshold} that ${that}, no finding`);
    return lines;
  }
  if (card.kind === "range") {
    const words = rangeWords(card);
    if (!p.n || p.p50 == null || p.p95 == null) return [when, `No ${words.noun}`];
    const b = card.baseline;
    return [
      when,
      `${words.high} (p95) ${formatValue(card, p.p95)}`,
      `Typical (p50) ${formatValue(card, p.p50)}`,
      plural(p.n, words.one, words.noun),
      b?.p50 != null && b.p95 != null
        ? `Baseline ${formatValue(card, b.p95)} and ${formatValue(card, b.p50)}`
        : "No baseline yet",
    ];
  }
  const words = rateWords(card);
  const checked = p.checked ?? 0;
  const flagged = p.flagged ?? 0;
  if (checked === 0) return [when, `No ${words.noun} checked`];
  const rate = flagged / checked;
  const base = card.baseline?.rate;
  let against = "No baseline yet";
  if (!card.learning && base != null) {
    const pp = (rate - base) * 100;
    const text = Math.abs(pp).toFixed(1);
    against = text === "0.0" ? "Same as baseline" : `${pp > 0 ? "+" : MINUS}${text} pp against baseline`;
  }
  return [
    when,
    `${percent(rate)} ${words.verb}`,
    `${flagged.toLocaleString("en-US")} of ${checked.toLocaleString("en-US")} ${words.noun}`,
    against,
  ];
}

/** Call sites in picker order: the most open cases, then the most turns, then by name. */
export function rankCallSites(options: ChartCallSiteOption[]): ChartCallSiteOption[] {
  return [...options].sort(
    (a, b) => b.open_cases - a.open_cases || b.turns - a.turns || a.call_site_id.localeCompare(b.call_site_id),
  );
}

/** The call site the page opens on: the first in picker order. */
export function defaultCallSite(options: ChartCallSiteOption[]): string | null {
  return rankCallSites(options)[0]?.call_site_id ?? null;
}

/** What a call site option says beside its name. */
export function callSiteMeta(o: ChartCallSiteOption): string {
  if (o.open_cases > 0) return plural(o.open_cases, "open case", "open cases");
  return o.learning ? "New, learning" : "No open cases";
}

/** What a tool option says beside its name. */
export function toolMeta(t: ChartToolOption): string {
  return t.open_cases > 0 ? plural(t.open_cases, "open case", "open cases") : "No open cases";
}

const byCasesThenCalls = (a: ChartToolOption, b: ChartToolOption) =>
  b.open_cases - a.open_cases || b.calls - a.calls || a.label.localeCompare(b.label);

/** The tool picker's groups: the tools this call site calls, then the rest. An empty group is left out. */
export function toolGroups(
  tools: ChartToolOption[],
  callSite: string | null,
): { label: string; tools: ChartToolOption[] }[] {
  const mine = tools.filter((t) => callSite != null && t.callers.includes(callSite)).sort(byCasesThenCalls);
  const others = tools.filter((t) => !mine.includes(t)).sort(byCasesThenCalls);
  return [
    { label: "Called by this call site", tools: mine },
    { label: "Other tools", tools: others },
  ].filter((g) => g.tools.length > 0);
}

/**
 * The tool the page opens on: one with an open case, this call site's first; else the tool this call site calls
 * most; else the most-called tool of all.
 */
export function defaultTool(tools: ChartToolOption[], callSite: string | null): string | null {
  const groups = toolGroups(tools, callSite);
  const mine = callSite != null && groups[0]?.label === "Called by this call site" ? groups[0].tools : [];
  const all = groups.flatMap((g) => g.tools);
  const pick =
    mine.find((t) => t.open_cases > 0) ??
    all.find((t) => t.open_cases > 0) ??
    [...mine].sort((a, b) => b.calls - a.calls)[0] ??
    [...all].sort((a, b) => b.calls - a.calls)[0];
  return pick?.tool_key ?? null;
}

/** "All call sites. Called by 2 call sites." beside the tool picker. */
export function callersText(t: ChartToolOption): string {
  if (t.callers.length === 0) return "All call sites. No call site called it in this range.";
  return `All call sites. Called by ${plural(t.callers.length, "call site", "call sites")}.`;
}

/** Why a classifier that is on cannot judge yet, as a short status. */
export function waitingWords(reason: string | null): string {
  if (reason === "no_schema" || reason === "waiting_on_schemas") return "Waiting for a schema";
  if (reason === "not_set_up") return "Waiting for setup";
  if (reason === "not_scoring") return "Not scoring";
  const pause = reason ? PROVIDER_PAUSES[reason] : undefined;
  if (pause) return `Paused, ${pause.label.charAt(0).toLowerCase()}${pause.label.slice(1)}`;
  return "Waiting";
}

/** A classifier's line in the Configure menu: where it runs, or why it does not. */
export function menuStatus(item: ClassifierMenuItem): string {
  if (item.status === "off") return "Off";
  if (item.status === "waiting") return waitingWords(item.waiting_reason);
  if (item.covers === "tools") return "On, every tool";
  if (item.covers === "call_sites_and_tools") return "On, call sites and tools";
  if (item.all_call_sites) return "On, every call site";
  if (item.call_site_count === 0) return "On, no call sites";
  return `On, ${plural(item.call_site_count, "call site", "call sites")}`;
}

/** A classifier with no card for this scope, and why. */
export function chipText(chip: ChartChip): string {
  if (chip.state === "off") return `${chip.name} off`;
  if (chip.state === "waiting") {
    const words = waitingWords(chip.reason);
    return `${chip.name} ${words.charAt(0).toLowerCase()}${words.slice(1)}`;
  }
  return chip.since ? `${chip.name} quiet since ${utcDay(chip.since)}` : `${chip.name} has no data yet`;
}
