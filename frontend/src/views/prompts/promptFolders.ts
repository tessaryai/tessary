// SPDX-License-Identifier: Apache-2.0
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

/** `latest` is derived from the highest version: never shown as editable, never sent, never a reason to block. */
export function labelsWithoutLatest(labels: string[]): string[] {
  return labels.filter((l) => l !== "latest");
}

/** A span's version string: `v3` when numeric, `(prod)` when a producer sent a label. */
export function formatVersionRef(version: string | null): string {
  if (version === null) return "";
  return /^[1-9]\d*$/.test(version) ? `v${version}` : `(${version})`;
}
