// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

export type Status = "pending" | "running" | "passed" | "failed" | "skipped";

type Visual = { dot: string; text: string; bgVar?: string };

/*
 * A status never wears the accent, which marks controls. `running` is blue but unfilled: a filled blue
 * element is always a control.
 */
const STYLE: Record<Status, Visual> = {
  pending:  { dot: "bg-muted",    text: "text-muted" },
  running:  { dot: "bg-info",     text: "text-info" },
  passed:   { dot: "bg-success",  text: "text-success", bgVar: "var(--color-success-subtle)" },
  failed:   { dot: "bg-error",    text: "text-error",   bgVar: "var(--color-error-subtle)" },
  skipped:  { dot: "bg-subtle",   text: "text-subtle" },
};

export function StatusPill({
  status,
  label,
}: {
  status: Status;
  label: string;
}) {
  const v = STYLE[status];
  const isAnimated = status === "running";
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-label",
        v.text,
      )}
      style={v.bgVar ? { backgroundColor: v.bgVar } : undefined}
    >
      <span
        className={cn("inline-block size-1.5 rounded-pill", v.dot, isAnimated && "animate-pulse")}
        aria-hidden="true"
      />
      {label}
    </span>
  );
}
