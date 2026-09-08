// SPDX-License-Identifier: Apache-2.0

/**
 * How long ago, in the coarsest unit that still says something: `40s ago`, `9h ago`, `3d ago`.
 *
 * Distinct from `timeAgo` in `views/triage/bits.tsx`, which switches to an absolute clock past the
 * hour (`Sep 4 14:22`). Both are correct for their surface: a case row is a thing you look up, so a
 * timestamp you can match against a deploy log wins; a staleness signal is a duration, and "the last
 * trace arrived Sep 4 14:22" makes the reader do the subtraction that is the entire point.
 */
export function relativeTime(iso: string | null): string {
  if (!iso) return "—";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0 || Number.isNaN(ms)) return "just now";
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}
