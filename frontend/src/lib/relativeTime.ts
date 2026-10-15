// SPDX-License-Identifier: Apache-2.0

/**
 * A staleness signal, so always a duration, unlike `timeAgo` in views/triage/bits.tsx, which switches
 * to a clock time to match against deploy logs.
 */
export function relativeTime(iso: string | null): string {
  if (!iso) return "—";
  const ms = Date.now() - new Date(iso).getTime();
  // A time ahead of this browser's clock is skew, not the future.
  if (ms < 0) return "just now";
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}
