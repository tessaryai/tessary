// SPDX-License-Identifier: Apache-2.0

/**
 * When a credential was created or last used, as the API key and MCP token ledgers show it: a duration
 * for the last month (`5m ago`, `3d ago`), then a date. Never-used reads as "Never", and a time ahead of
 * the browser's clock is shown as-is rather than as a negative age.
 *
 * Distinct from `relativeTime`, which is a staleness signal and never switches to a date.
 */
export function ledgerTime(iso: string | null): string {
  if (!iso) return "Never";
  const then = new Date(iso).getTime();
  const diff = Date.now() - then;
  if (diff < 0) return new Date(iso).toLocaleString();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return "Just now";
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
}
