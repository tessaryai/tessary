// SPDX-License-Identifier: Apache-2.0
const pad = (n: number) => String(n).padStart(2, "0");

/** datetime-local wall-time string ("YYYY-MM-DDTHH:mm") -> ISO-8601 UTC, or null. */
export function localToIso(v: string): string | null {
  if (!v) return null;
  const d = new Date(v);
  return isNaN(d.getTime()) ? null : d.toISOString();
}

/** ISO-8601 -> datetime-local wall-time string, or "". */
export function isoToLocal(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "";
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
