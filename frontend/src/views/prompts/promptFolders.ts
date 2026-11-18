// SPDX-License-Identifier: Apache-2.0
import type { PromptSummary } from "../../api/types";

export function leafName(name: string): string {
  return name.slice(name.lastIndexOf("/") + 1);
}

export function folderOf(name: string): string {
  const i = name.lastIndexOf("/");
  return i < 0 ? "" : name.slice(0, i);
}

/** Null at the root, so a writer deletes `?folder=` rather than setting it empty. */
export function parentFolder(folder: string): string | null {
  const i = folder.lastIndexOf("/");
  return i < 0 ? null : folder.slice(0, i);
}

export function folderCrumbs(folder: string): { label: string; folder: string }[] {
  if (!folder) return [];
  const parts = folder.split("/");
  return parts.map((label, i) => ({ label, folder: parts.slice(0, i + 1).join("/") }));
}

export type ListEntry =
  | { kind: "folder"; label: string; folder: string; count: number; updatedAt: string }
  | { kind: "prompt"; label: string; prompt: PromptSummary };

/** The entries directly under `folder`: its subfolders first, then its prompts, each alphabetical. */
export function groupByFolder(items: PromptSummary[], folder: string): ListEntry[] {
  const prefix = folder ? `${folder}/` : "";
  const folders = new Map<string, { count: number; updatedAt: string }>();
  const prompts: ListEntry[] = [];
  for (const item of items) {
    if (!item.name.startsWith(prefix)) continue;
    const rest = item.name.slice(prefix.length);
    const slash = rest.indexOf("/");
    if (slash < 0) {
      prompts.push({ kind: "prompt", label: rest, prompt: item });
      continue;
    }
    const label = rest.slice(0, slash);
    const seen = folders.get(label);
    if (!seen) folders.set(label, { count: 1, updatedAt: item.updated_at });
    else {
      seen.count += 1;
      if (item.updated_at > seen.updatedAt) seen.updatedAt = item.updated_at;
    }
  }
  const byLabel = (a: ListEntry, b: ListEntry) => a.label.localeCompare(b.label);
  const folderEntries: ListEntry[] = [...folders].map(([label, f]) => ({
    kind: "folder",
    label,
    folder: prefix + label,
    count: f.count,
    updatedAt: f.updatedAt,
  }));
  return [...folderEntries.sort(byLabel), ...prompts.sort(byLabel)];
}

/** `latest` is derived from the highest version: never shown as editable, never sent, never a reason to block. */
export function labelsWithoutLatest(labels: string[]): string[] {
  return labels.filter((l) => l !== "latest");
}

/** A span's version string: `v3` when numeric, `(prod)` when a producer sent a label. */
export function formatVersionRef(version: string | null): string {
  if (version === null) return "";
  return /^\d+$/.test(version) ? `v${version}` : `(${version})`;
}
