// SPDX-License-Identifier: Apache-2.0

/** A duration for the last month, then a date. A time ahead of the browser's clock is shown as-is. */
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
