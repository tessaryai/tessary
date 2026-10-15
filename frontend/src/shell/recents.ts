// SPDX-License-Identifier: Apache-2.0
export type RecentEntry = {
  path: string;
  label: string;
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
  }
}
