// SPDX-License-Identifier: Apache-2.0
import { Fragment } from "react";
import { cn } from "../../ui";
import type { TraceDetailView } from "../../api/types";

export type DetectionMark = TraceDetailView["detections"][number];

/** The Detected by value that matches a flag by any classifier. */
export const ANY_DETECTION = "any";

/**
 * Whether a mark judged the person's turn (Frustration), drawn on their message, rather than one span's
 * output (Secret Leak), drawn on what that span said.
 */
export const isTurnMark = (d: DetectionMark) => d.grain === "trace";

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

/** Conversation's marks: each judged turn by trace id, each judged span's output by span id. */
export function conversationMarks(detections: DetectionMark[]): {
  byTrace: Map<string, string[]>;
  bySpan: Map<string, string[]>;
} {
  return {
    byTrace: namesBy(detections.filter(isTurnMark), "trace_id"),
    bySpan: namesBy(detections.filter((d) => !isTurnMark(d)), "span_id"),
  };
}

/**
 * Where a classifier flagged something: a small red dot and the classifier's name, quiet on purpose so a
 * flagged row or message reads first as itself.
 */
export function DetectionMarker({
  names,
  picked = [],
  size = "label",
  className,
}: {
  names: string[];
  /** Classifiers the reader filtered by: they lead, in full text color. */
  picked?: string[];
  size?: "label" | "small";
  className?: string;
}) {
  if (names.length === 0) return null;
  const lead = picked.filter((n) => names.includes(n));
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
      <span className="truncate">
        {lead.length === 0
          ? names.join(", ")
          : [...lead, ...names.filter((n) => !picked.includes(n))].map((n, i) => (
              <Fragment key={n}>
                {i > 0 && ", "}
                <span className={picked.includes(n) ? "text-fg" : undefined}>{n}</span>
              </Fragment>
            ))}
      </span>
    </span>
  );
}
