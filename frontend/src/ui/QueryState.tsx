// SPDX-License-Identifier: Apache-2.0
import { ApiError } from "../api/types";
import { Spinner, Skeleton } from "./Spinner";
import { cn } from "./cn";

/**
 * Shared loading / error / empty state primitives.
 *
 * Surfaces across the app hand-rolled the same three states inline; these wrap
 * the design-system primitives (`Spinner`, `Skeleton`, `EmptyState`) so every
 * surface renders them identically, accessibly, and with consistent spacing.
 * They are deliberately small and composable — a view can use {@link LoadingRow}
 * for an inline "Loading…" line, {@link ErrorNote} for a query failure, or
 * {@link TableSkeleton} for a data-dense table's loading frame.
 */

/** Inline "Loading…" line — spinner + muted text, polite to screen readers. */
export function LoadingRow() {
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-2 text-small text-muted">
      <Spinner size="sm" />
      <span>Loading…</span>
    </div>
  );
}

/** A query failure, rendered as an alert. `error` may be any caught value. */
export function ErrorNote({ error, className }: { error: unknown; className?: string }) {
  const code = (error as { code?: string } | null)?.code;
  // `ApiError.detail` rather than `.message`: the latter is already prefixed with the code, and the
  // code is rendered separately below.
  const message =
    error instanceof ApiError
      ? error.detail
      : error instanceof Error
        ? error.message
        : typeof error === "string"
          ? error
          : "The request failed. Try again.";
  return (
    <p role="alert" className={cn("text-small text-error", className)}>
      {code && <code className="font-mono">{code}</code>}
      {code ? ": " : ""}
      {message}
    </p>
  );
}

/** A skeleton placeholder shaped like a table, for the loading frame of data-dense views. */
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
