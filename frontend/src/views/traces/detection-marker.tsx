// SPDX-License-Identifier: Apache-2.0
import { cn } from "../../ui";
import type { TraceDetailView } from "../../api/types";

export type DetectionMark = TraceDetailView["detections"][number];

/**
 * The detectors whose flags the trace and session views show. The server keeps the same set; a classifier
 * outside it is not offered in the Detected by filter.
 */
export const DETECTED_BY_DETECTORS = new Set(["frustration"]);

/** The Detected by value that matches a flag by any classifier. */
export const ANY_DETECTION = "any";

/** The classifier names, by trace id or by span id, for the views to mark. */
export function namesBy(detections: DetectionMark[], key: "trace_id" | "span_id"): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const d of detections) {
    const id = d[key];
    if (id == null) continue;
    const names = out.get(id) ?? [];
    if (!names.includes(d.name)) names.push(d.name);
    out.set(id, names);
  }
  return out;
}

/**
 * Where a classifier flagged something: a small red dot and the classifier's name, quiet on purpose so a
 * flagged row or message reads first as itself.
 */
export function DetectionMarker({
  names,
  size = "label",
  className,
}: {
  names: string[];
  size?: "label" | "small";
  className?: string;
}) {
  if (names.length === 0) return null;
  return (
    <span
      className={cn(
        "inline-flex min-w-0 items-center gap-1.5 text-muted",
        size === "small" ? "text-small" : "text-label",
        className,
      )}
    >
      <span aria-hidden="true" className="shrink-0 rounded-pill bg-error" style={{ width: 6, height: 6 }} />
      <span className="sr-only">Flagged by </span>
      <span className="truncate">{names.join(", ")}</span>
    </span>
  );
}
