// SPDX-License-Identifier: Apache-2.0
import { ApiError } from "../api/types";
import { Spinner, Skeleton } from "./Spinner";
import { cn } from "./cn";

export function LoadingRow({ label = "Loading…", className }: { label?: string; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={cn("flex items-center gap-2 text-small text-muted", className)}>
      <Spinner size="sm" />
      <span>{label}</span>
    </div>
  );
}

export function ErrorNote({ error, className }: { error: unknown; className?: string }) {
  const code = (error as { code?: string } | null)?.code;
  // `detail`, not `message`, which already carries the code rendered separately below.
  const message =
    (error instanceof ApiError
      ? error.detail
      : error instanceof Error
        ? error.message
        : typeof error === "string"
          ? error
          : "") || "The request failed. Try again.";
  return (
    <p role="alert" className={cn("text-small text-error", className)}>
      {code && <code className="font-mono">{code}</code>}
      {code ? ": " : ""}
      {message}
    </p>
  );
}

export function TableSkeleton({ rows = 6, cols = 4, className }: { rows?: number; cols?: number; className?: string }) {
  return (
    <div
      className={cn("rounded-card border border-border overflow-hidden", className)}
      role="status"
      aria-label="Loading table"
    >
      <div className="flex gap-3 px-3 py-2 bg-surface border-b border-border">
        {Array.from({ length: cols }).map((_, i) => (
          <Skeleton key={i} className="h-3 flex-1" />
        ))}
      </div>
      <div className="divide-y divide-border">
        {Array.from({ length: rows }).map((_, r) => (
          <div key={r} className="flex gap-3 px-3 py-2.5">
            {Array.from({ length: cols }).map((_, c) => (
              <Skeleton key={c} className="h-3.5 flex-1" style={{ opacity: 1 - r * 0.07 }} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
