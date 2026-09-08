// SPDX-License-Identifier: Apache-2.0
/*
 * Client-side "recently visited" ring buffer, per org/project, persisted in localStorage.
 *
 * Powers the command palette's "Recent" group with zero backend dependency — it remembers
 * the labelled routes the user has already visited this/last session so ⌘K feels personal
 * from day two. This is intentionally NOT server-backed search (deferred; see decision.md);
 * it is purely a local navigation aid.
 */
export type RecentEntry = {
  /** Absolute, tenant-scoped route path. */
  path: string;
  /** Human label shown in the palette. */
  label: string;
  /** Epoch ms of the last visit (most-recent first). */
  at: number;
};

const MAX_RECENTS = 6;

function key(orgSlug: string, projectSlug: string) {
  return `tessary.recents.${orgSlug}/${projectSlug}`;
}

export function readRecents(orgSlug: string, projectSlug: string): RecentEntry[] {
  try {
    const raw = localStorage.getItem(key(orgSlug, projectSlug));
    if (!raw) return [];
    const parsed = JSON.parse(raw) as RecentEntry[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Record a visit, de-duplicating by path and keeping the buffer bounded + newest-first. */
export function pushRecent(
  orgSlug: string,
  projectSlug: string,
  entry: { path: string; label: string },
): void {
  try {
    const now = Date.now();
    const existing = readRecents(orgSlug, projectSlug).filter((r) => r.path !== entry.path);
    const next = [{ ...entry, at: now }, ...existing].slice(0, MAX_RECENTS);
    localStorage.setItem(key(orgSlug, projectSlug), JSON.stringify(next));
  } catch {
    // localStorage unavailable (private mode) — recents are best-effort only.
  }
}
