// SPDX-License-Identifier: Apache-2.0
// No Status column (a red dot on the name marks errors) and no Models column: the list row is a
// single-table trace read, and models are a span fact. Both filters stay.
import { useCallback, useState } from "react";
import { Button, cn } from "../../ui";
import { useDismissOnOutside } from "./index-filters";

export type ColumnKey =
  | "when"
  | "name"
  | "input"
  | "output"
  | "detectedBy"
  | "latency"
  | "cost"
  | "costIn"
  | "costOut"
  | "spans"
  | "errors"
  | "tokensIn"
  | "tokensOut"
  | "cacheRead"
  | "cacheWrite"
  | "reasoning"
  | "tokens"
  | "callSite"
  | "endedAt"
  | "traceId"
  | "session";

export type ColumnDef = { key: ColumnKey; label: string; numeric?: boolean; flex?: boolean; width: number };

/**
 * Declaration order is display order. `width` is fixed (the table uses `table-layout: fixed`), so toggling
 * grouped mode or expanding a session never resizes columns. `flex` widths are a floor; see `columnWidths`.
 */
export const COLUMN_DEFS: ColumnDef[] = [
  { key: "when", label: "Start time", width: 150 },
  { key: "name", label: "Name", flex: true, width: 260 },
  { key: "input", label: "Input", flex: true, width: 280 },
  { key: "output", label: "Output", flex: true, width: 280 },
  { key: "detectedBy", label: "Detected by", width: 150 },
  // Wide enough for the longest tier ("3d 2h 15m 30s") without truncating under table-layout: fixed.
  { key: "latency", label: "Latency", numeric: true, width: 140 },
  { key: "cost", label: "Cost ($)", numeric: true, width: 90 },
  { key: "costIn", label: "Input cost ($)", numeric: true, width: 110 },
  { key: "costOut", label: "Output cost ($)", numeric: true, width: 120 },
  { key: "spans", label: "Spans", numeric: true, width: 80 },
  { key: "errors", label: "Errors", numeric: true, width: 80 },
  { key: "tokensIn", label: "Input tokens", numeric: true, width: 120 },
  { key: "tokensOut", label: "Output tokens", numeric: true, width: 130 },
  { key: "cacheRead", label: "Cache read tokens", numeric: true, width: 150 },
  { key: "cacheWrite", label: "Cache write tokens", numeric: true, width: 155 },
  { key: "reasoning", label: "Reasoning tokens", numeric: true, width: 140 },
  { key: "tokens", label: "Total tokens", numeric: true, width: 120 },
  { key: "callSite", label: "Call site", width: 170 },
  { key: "endedAt", label: "End time", width: 150 },
  { key: "traceId", label: "Trace ID", width: 200 },
  { key: "session", label: "Session ID", width: 200 },
];

/**
 * The `<colgroup>` widths for the visible columns. Surplus width beyond the column sum goes to the `flex`
 * columns in proportion, not spread over the numeric ones. `extraFixedPx` is the pinned expand column.
 */
export function columnWidths(columns: ColumnDef[], extraFixedPx = 0): (string | number)[] {
  const flexTotal = columns.reduce((sum, c) => (c.flex ? sum + c.width : sum), 0);
  if (flexTotal === 0) return columns.map((c) => c.width);
  const fixedPx = columns.reduce((sum, c) => (c.flex ? sum : sum + c.width), extraFixedPx);
  return columns.map((c) =>
    c.flex ? `calc((100% - ${fixedPx}px) * ${(c.width / flexTotal).toFixed(6)})` : c.width,
  );
}

/** The pinned expand-chevron column's width, in grouped mode — shared with its TD/TH styling below. */
export const SESSION_EXPAND_COLUMN_WIDTH = 40;

const DEFAULT_VISIBLE: ColumnKey[] = ["when", "name", "input", "output", "detectedBy", "latency", "cost", "tokens", "spans"];

/** A new key rather than a migration: the old set named columns that no longer exist. */
const STORAGE_KEY = "tessary:traces:columns:v2";
const ALL_KEYS = new Set<string>(COLUMN_DEFS.map((c) => c.key));

function load(): Set<ColumnKey> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        // Unknown keys are dropped, so a saved set keeps whatever still exists.
        const keys = parsed.filter((k): k is ColumnKey => typeof k === "string" && ALL_KEYS.has(k));
        if (keys.length > 0) return new Set(keys);
      }
    }
  } catch {
    // Corrupt or unavailable storage — fall through to the default set.
  }
  return new Set(DEFAULT_VISIBLE);
}

export function useColumnConfig(): {
  visible: Set<ColumnKey>;
  toggle: (key: ColumnKey) => void;
  reset: () => void;
} {
  const [visible, setVisible] = useState<Set<ColumnKey>>(load);

  const persist = (next: Set<ColumnKey>) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([...next]));
    } catch {
      // Storage unavailable — the choice just doesn't persist.
    }
    return next;
  };

  const toggle = (key: ColumnKey) => {
    setVisible((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        if (next.size === 1) return prev; // never hide the last column
        next.delete(key);
      } else {
        next.add(key);
      }
      return persist(next);
    });
  };

  const reset = () => setVisible(() => persist(new Set(DEFAULT_VISIBLE)));

  return { visible, toggle, reset };
}

/** The `Columns` header action — secondary sm button + checkbox popover. */
export function ColumnsControl({
  visible,
  onToggle,
  onReset,
}: {
  visible: Set<ColumnKey>;
  onToggle: (key: ColumnKey) => void;
  onReset: () => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useDismissOnOutside(
    open,
    useCallback(() => setOpen(false), []),
  );

  return (
    <div ref={rootRef} className="relative">
      <Button size="sm" aria-expanded={open} aria-haspopup="true" onClick={() => setOpen((o) => !o)}>
        Columns
        <span className="text-subtle ml-1.5 text-label">
          {visible.size}
        </span>
      </Button>
      {open && (
        <div
          className="absolute right-0 top-full z-30 mt-1.5 rounded-card border border-border bg-raised p-1.5 shadow-lg"
          style={{ minWidth: 196, maxHeight: 420, overflowY: "auto" }}
          role="group"
          aria-label="Visible columns"
        >
          {COLUMN_DEFS.map((col) => {
            const on = visible.has(col.key);
            return (
              <button
                key={col.key}
                type="button"
                aria-pressed={on}
                onClick={() => onToggle(col.key)}
                className="flex w-full items-center gap-2 rounded-control px-2 py-1.5 text-small text-fg transition-colors hover:bg-hover">
                <span
                  aria-hidden="true"
                  className={cn(
                    "inline-flex size-3.5 shrink-0 items-center justify-center rounded-micro border",
                    on
                      ? "border-accent bg-accent text-[color:var(--color-accent-text-on)]"
                      : "border-border-strong",
                  )}
                >
                  {on && (
                    <svg width="9" height="9" viewBox="0 0 9 9" fill="none">
                      <path
                        d="M1.5 4.5L3.5 6.5L7.5 2.5"
                        stroke="currentColor"
                        strokeWidth="1.5"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                      />
                    </svg>
                  )}
                </span>
                <span className="truncate">{col.label}</span>
              </button>
            );
          })}
          <div className="border-t border-border mt-1 pt-1">
            <button
              type="button"
              onClick={onReset}
              className="flex w-full items-center rounded-control px-2 py-1.5 text-small text-muted transition-colors hover:bg-hover hover:text-fg">
              Reset to default
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
