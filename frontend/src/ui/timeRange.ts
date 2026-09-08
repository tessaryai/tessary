// SPDX-License-Identifier: Apache-2.0
/**
 * A time-range bound is either a relative token ("now", "now-1d"), an absolute
 * ISO-8601 string, or null (unbounded). The backend's RelativeTime resolver uses
 * the same grammar, so these strings travel straight into the filter.
 */
export type TimeRange = { from: string | null; to: string | null };

export type RelUnit = "m" | "h" | "d" | "w";

const RELATIVE = /^now(?:-(\d+)([mhdw]))?$/;

export type Relative = { n: number; unit: RelUnit };

/** Parse a relative token; null if the value isn't a `now…` token. "now" => n=0. */
export function parseRelative(token: string | null | undefined): Relative | null {
  if (!token) return null;
  const m = RELATIVE.exec(token.trim());
  if (!m) return null;
  if (m[1] == null) return { n: 0, unit: "m" };
  return { n: Number(m[1]), unit: m[2] as RelUnit };
}

export function formatRelativeToken(n: number, unit: RelUnit): string {
  return n === 0 ? "now" : `now-${n}${unit}`;
}

const UNIT_LABEL: Record<RelUnit, string> = { m: "minute", h: "hour", d: "day", w: "week" };

/** Human label for a single bound, e.g. "1 day ago", "now", "May 3, 2026, 4:00 PM", "—". */
export function formatBound(token: string | null | undefined): string {
  if (!token) return "—";
  const rel = parseRelative(token);
  if (rel) {
    if (rel.n === 0) return "now";
    const noun = UNIT_LABEL[rel.unit];
    return `${rel.n} ${noun}${rel.n === 1 ? "" : "s"} ago`;
  }
  const d = new Date(token);
  return isNaN(d.getTime()) ? token : d.toLocaleString();
}

/** Compact "A → B" label for a whole range. */
export function formatRange(range: TimeRange): string {
  if (!range.from && !range.to) return "all time";
  return `${formatBound(range.from)} → ${formatBound(range.to ?? "now")}`;
}

const pad = (n: number) => String(n).padStart(2, "0");

/** datetime-local wall-time string ("YYYY-MM-DDTHH:mm") -> ISO-8601 UTC, or null. */
export function localToIso(v: string): string | null {
  if (!v) return null;
  const d = new Date(v);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

/** ISO-8601 -> datetime-local wall-time string for the DateTimePicker, or "". */
export function isoToLocal(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
