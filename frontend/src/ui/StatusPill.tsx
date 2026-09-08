// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

export type Status =
  | "pending"
  | "queued"
  | "running"
  | "passed"
  | "failed"
  | "skipped"
  | "errored"
  | "orphaned"
  | "accepted"
  | "rejected"
  | "edited";

type Visual = { dot: string; text: string; bgVar?: string };

/*
 * A status never wears --color-accent. The accent is the interactive treatment (a thing you
 * click); a pill is a verdict about data. The three status hues carry the verdicts — red failed,
 * green passed, blue running/info — and every other state falls back to the grey ramp's reading
 * steps (muted, subtle, fg-secondary), never to the accent.
 *
 * `running` is blue but deliberately UNFILLED: the palette allows blue as a status only as a dot,
 * because a filled blue element in this system is always a control.
 */
const STYLE: Record<Status, Visual> = {
  pending:  { dot: "bg-muted",    text: "text-muted" },
  queued:   { dot: "bg-muted",    text: "text-muted" },
  running:  { dot: "bg-info",     text: "text-info" },
  passed:   { dot: "bg-success",  text: "text-success", bgVar: "var(--color-success-subtle)" },
  accepted: { dot: "bg-success",  text: "text-success", bgVar: "var(--color-success-subtle)" },
  failed:   { dot: "bg-error",    text: "text-error",   bgVar: "var(--color-error-subtle)" },
  rejected: { dot: "bg-error",    text: "text-error",   bgVar: "var(--color-error-subtle)" },
  errored:  { dot: "bg-error",    text: "text-error",   bgVar: "var(--color-error-subtle)" },
  skipped:  { dot: "bg-subtle",   text: "text-subtle" },
  orphaned: { dot: "bg-warning",  text: "text-warning", bgVar: "var(--color-warning-subtle)" },
  edited:   { dot: "bg-fg-secondary", text: "text-fg-secondary" },
};

const LABEL: Record<Status, string> = {
  pending: "Pending",
  queued: "Queued",
  running: "Running",
  passed: "Passed",
  failed: "Failed",
  skipped: "Skipped",
  errored: "Errored",
  orphaned: "Orphaned",
  accepted: "Accepted",
  rejected: "Rejected",
  edited: "Edited",
};

export function StatusPill({
  status,
  label,
  className,
}: {
  status: Status;
  label?: string;
  className?: string;
}) {
  const v = STYLE[status];
  const isAnimated = status === "running";
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-label",
        v.text,
        className,
      )}
      style={v.bgVar ? { backgroundColor: v.bgVar } : undefined}
    >
      <span
        className={cn("inline-block size-1.5 rounded-pill", v.dot, isAnimated && "animate-pulse")}
        aria-hidden="true"
      />
      {label ?? LABEL[status]}
    </span>
  );
}
