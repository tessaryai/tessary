// SPDX-License-Identifier: Apache-2.0
/*
 * Traces index — the column set and per-user column config.
 *
 * Every column the list API can serve is here and every one is toggleable; the
 * choice persists to localStorage per browser. The default set answers "what
 * ran, what did it say, how long, what did it cost" — the questions a
 * monitoring table is opened for, including a content preview so the table
 * doesn't read as a bare metrics report. The rest are opt-in because they are
 * either wide (the per-direction cost/token breakdowns), or identifiers you go
 * looking for deliberately rather than scan (trace id, session id).
 *
 * No Status column. Runtime status is binary and nearly always `ok`, so a column
 * restating that costs more width than it returns; a trace that errored carries
 * a red dot on its name instead. The Status FILTER stays — narrowing to errors
 * is a real question.
 *
 * No Models column either, and its absence is a wire fact rather than a taste:
 * the v2 list row is a single-table read of one trace row, and "which models did
 * this turn use" is a question about its spans. It was served before by
 * aggregating every observation in the project on every request, which is the
 * cost this milestone exists to remove. The models a turn used are in its detail;
 * the Model FILTER stays, because filtering is a semi-join rather than an
 * aggregate.
 */
import { useCallback, useState } from "react";
import { Button, cn } from "../../ui";
import { useDismissOnOutside } from "./index-filters";

export type ColumnKey =
  | "when"
  | "name"
  | "input"
  | "output"
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
  | "session"
  | "sessionExpand";

export type ColumnDef = { key: ColumnKey; label: string; numeric?: boolean; flex?: boolean; width: number };

/**
 * Declaration order is display order — the table renders whichever of these are visible, in this
 * order. `width` is a fixed pixel width, not a hint: the table renders with `table-layout: fixed`
 * against a `<colgroup>` built from these, specifically so a column's width never depends on what
 * happens to be in it on a given render. Before this, width came from the browser's auto layout
 * scanning every cell's content — which meant Input/Output (long text preview vs. a bare "—" on a
 * session rollup row) would visibly resize the whole table on every toggle between flat and
 * grouped-by-session, and again on every session expand/collapse. Fixed widths make both silent.
 *
 * `flex` marks the columns whose width is a floor rather than a fixed size — see `columnWidths`.
 */
export const COLUMN_DEFS: ColumnDef[] = [
  { key: "when", label: "Start time", width: 150 },
  { key: "name", label: "Name", flex: true, width: 260 },
  { key: "input", label: "Input", flex: true, width: 280 },
  { key: "output", label: "Output", flex: true, width: 280 },
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
 * The `<colgroup>` widths, as CSS width values, for the visible columns in display order.
 *
 * The table is `width: 100%` with the column sum as its `min-width`: narrower than the sum it
 * scrolls at exactly these widths, wider than the sum the surplus has to go somewhere. Left to the
 * browser the surplus is spread across every column, which pads the numeric ones that were already
 * sized to their longest value and leaves the text previews truncating anyway. So it goes to the
 * `flex` columns instead, split in proportion to their declared widths — on a wide monitor the
 * table fills the page and Input/Output show more of the payload, which is the point of them.
 *
 * `extraFixedPx` is width the table carries outside `columns` (the pinned expand column in grouped
 * mode). With no flex column visible there is nothing to hand the surplus to, so the declared
 * widths are returned as-is and the browser's own distribution is left alone.
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

const DEFAULT_VISIBLE: ColumnKey[] = ["when", "name", "input", "output", "latency", "cost", "tokens", "spans"];

/**
 * Bumped from `tessary:traces:columns` with the v2 wire.
 *
 * The old key holds a set naming columns this table no longer has (`models`, `toolCalls`) and missing
 * the ones it gained. The loader drops unknown keys, so a stale set would have silently shrunk to
 * whatever still existed — a returning user's table losing columns for no visible reason. A new key
 * gives everyone the current default once, and the old value is simply abandoned rather than migrated:
 * it encodes preferences about a column set that no longer exists.
 */
const STORAGE_KEY = "tessary:traces:columns:v2";
const ALL_KEYS = new Set<string>(COLUMN_DEFS.map((c) => c.key));

function load(): Set<ColumnKey> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        // Unknown keys are dropped rather than rejected, so a saved set from an
        // older column list keeps whatever still exists instead of resetting.
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

  // Twenty-one columns is enough that turning them off one by one is a chore.
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
