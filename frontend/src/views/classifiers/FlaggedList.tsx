// SPDX-License-Identifier: Apache-2.0
// The pane draws the real traces, not the scored request: that copy is clipped, text-only and cleared
// by retention. Only the selected row's traces are fetched, keyed like the trace page's read.
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  infiniteQueryOptions,
  keepPreviousData,
  useInfiniteQuery,
  useQueries,
  useQueryClient,
} from "@tanstack/react-query";
import { useTenant } from "../../tenant/TenantContext";
import { Button, Skeleton, cn } from "../../ui";
import { SessionConversationView, type Emphasis, type EmphasisBySpan } from "../traces/detail-views";
import { spanOrder, type Span } from "../traces/detail-data";
import { useListPaging } from "./useListPaging";
import { useSkeletonFlag } from "./useSkeletonFlag";

const FLAGGED_BOTTOM_GAP = 24;

/** The most rows a reader can pick at once. */
const MAX_PICKS = 20;

const FRAME = "grid rounded-card border border-border overflow-hidden bg-surface";
const FRAME_STYLE = { gridTemplateColumns: "320px minmax(0, 1fr)", height: 720 };

const DAY_TIME: Intl.DateTimeFormatOptions = {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
};

export type RowFilter = { rcaReport: string; index: number };

/** A `question` or `reply` entry is on the trace's root span, so its `spanId` may be null. */
export type FlaggedEmphasis = Emphasis & { traceId: string; spanId: string | null };

export type FlaggedRow = {
  id: string;
  traceId: string;
  score: number | null;
  flaggedAt: string | null;
  cleared: boolean;
  sessionId: string | null;
  contextTraceIds: string[];
  preview: string | null;
  emphasis: FlaggedEmphasis[];
};

export type FlaggedPage = { rows: FlaggedRow[]; nextCursor: string | null; total: number };

export type FlaggedLoader = (filter: RowFilter | undefined, cursor: string | null) => Promise<FlaggedPage>;

const filterKeyOf = (filter: RowFilter | undefined) => (filter ? `${filter.rcaReport}:${filter.index}` : "all");

const rowKey = (row: FlaggedRow) => `${row.id} ${row.traceId}`;

function rowsQuery(base: string, listKey: string, loadPage: FlaggedLoader, filter: RowFilter | undefined) {
  return infiniteQueryOptions({
    queryKey: ["flagged-rows", base, listKey, filterKeyOf(filter)],
    queryFn: ({ pageParam }: { pageParam: string | null }) => loadPage(filter, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last: FlaggedPage) => last.nextCursor ?? undefined,
    staleTime: Infinity,
  });
}

export function FlaggedList({
  first,
  loadPage,
  listKey,
  filter,
  readAhead = [],
  basePath,
  listLabel,
  unit,
  emptyText,
  missingText,
  dim = true,
  contextNote,
  rowAction,
  selectionActions,
}: {
  first?: FlaggedPage;
  loadPage: FlaggedLoader;
  listKey: string;
  filter?: RowFilter;
  readAhead?: RowFilter[];
  basePath: string;
  listLabel: string;
  unit: [one: string, many: string];
  emptyText: string;
  missingText: string;
  dim?: boolean;
  contextNote?: string;
  /** Drawn in the pane's header for the open row. */
  rowAction?: (row: FlaggedRow) => ReactNode;
  /** Passing it gives each row a checkbox; drawn in a bar above the list for every picked row, in pick order. */
  selectionActions?: (rows: FlaggedRow[]) => ReactNode;
}) {
  const { api } = useTenant();
  const [one, many] = unit;
  const filterKey = filterKeyOf(filter);
  const pages = useInfiniteQuery({
    ...rowsQuery(api.base, listKey, loadPage, filter),
    initialData: filter || !first ? undefined : { pages: [first], pageParams: [null] },
    placeholderData: keepPreviousData,
  });
  const skeleton = useSkeletonFlag(pages.isPlaceholderData);

  const queryClient = useQueryClient();
  const readAheadKey = readAhead.map(filterKeyOf).join(",");
  useEffect(() => {
    for (const f of readAhead) void queryClient.prefetchInfiniteQuery(rowsQuery(api.base, listKey, loadPage, f));
    // readAheadKey stands in for readAhead, which is a fresh array on every render, and loadPage is read
    // only for a page not cached yet.
  }, [queryClient, api.base, listKey, readAheadKey]);
  const shown = pages.data?.pages.flatMap((p) => p.rows) ?? [];
  const total = pages.data?.pages[0]?.total ?? first?.total ?? 0;

  const [opened, setOpened] = useState<{ filterKey: string; key: string } | null>(null);
  const openedKey = opened?.filterKey === filterKey ? opened.key : null;
  const selected = shown.find((r) => rowKey(r) === openedKey) ?? shown[0];

  // Picks hold the row itself, not its place, so they outlive Load more and a filter that hides them.
  const [picks, setPicks] = useState<{ listKey: string; rows: FlaggedRow[] }>({ listKey, rows: [] });
  const picked = picks.listKey === listKey ? picks.rows : [];
  const pickedKeys = new Set(picked.map(rowKey));
  const full = picked.length >= MAX_PICKS;
  const togglePick = (row: FlaggedRow) => {
    const key = rowKey(row);
    setPicks({
      listKey,
      rows: pickedKeys.has(key) ? picked.filter((r) => rowKey(r) !== key) : [...picked, row],
    });
  };
  const bar = selectionActions && picked.length > 0 && (
    <div
      role="toolbar"
      aria-label={`Selected ${many}`}
      className="mb-2.5 flex flex-wrap items-center gap-3 rounded-card border border-border-strong bg-raised py-2 pr-2.5 pl-3.5 text-small text-fg"
    >
      <span>{picked.length} selected</span>
      {selectionActions(picked)}
      {full && (
        <span className="text-muted">
          You can pick up to {MAX_PICKS} {many}.
        </span>
      )}
      <Button size="sm" variant="ghost" className="ml-auto" onClick={() => setPicks({ listKey, rows: [] })}>
        Clear
      </Button>
    </div>
  );
  // The bar takes the first slot even when it is empty, so a first pick never remounts the list beside it.
  const withBar = (content: ReactNode) => (
    <>
      {bar}
      {content}
    </>
  );

  const { isFetchingNextPage, fetchNextPage } = pages;
  const { list, end, hasNextPage } = useListPaging(filterKey, pages);

  if (pages.isLoading || skeleton) {
    return withBar(<RowsSkeleton label={`Loading ${many}`} />);
  }
  if (shown.length === 0) {
    return withBar(
      <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
        {emptyText}
      </p>,
    );
  }

  return withBar(
    <div className={FRAME} style={FRAME_STYLE}>
      <div className="flex flex-col min-h-0 border-r border-border">
        <ul ref={list} className="m-0 p-0 list-none overflow-y-auto flex-1" aria-label={listLabel}>
          {shown.map((r) => {
            const key = rowKey(r);
            const on = key === (selected && rowKey(selected));
            const isPicked = pickedKeys.has(key);
            return (
              <li
                key={key}
                className={cn(
                  "flex border-b border-border transition-colors",
                  on ? "bg-raised" : "bg-surface hover:bg-hover",
                )}
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                {selectionActions && (
                  <input
                    type="checkbox"
                    aria-label={`Pick this ${one}`}
                    checked={isPicked}
                    disabled={full && !isPicked}
                    onChange={() => togglePick(r)}
                    className="mt-3.5 ml-4 size-4 shrink-0 cursor-pointer accent-accent disabled:cursor-not-allowed"
                  />
                )}
                <button
                  type="button"
                  aria-pressed={on}
                  onClick={() => setOpened({ filterKey, key })}
                  className={cn(
                    "flex min-w-0 flex-1 flex-col text-left cursor-pointer py-3 pr-4",
                    selectionActions ? "pl-2.5" : "pl-4",
                  )}
                >
                  <span className="flex justify-between font-mono text-small text-muted">
                    <span>{r.flaggedAt ? new Date(r.flaggedAt).toLocaleString(undefined, DAY_TIME) : "–"}</span>
                    <span className="tabular-nums">{r.score != null ? r.score.toFixed(2) : "–"}</span>
                  </span>
                  <span
                    className={cn("mt-1 text-small", on ? "text-fg font-medium" : "text-fg-secondary")}
                    style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
                  >
                    {r.preview ?? <span className="font-mono text-muted">{r.id}</span>}
                  </span>
                  {r.cleared && <span className="mt-1 text-small text-muted">Cleared</span>}
                </button>
              </li>
            );
          })}
          {hasNextPage && (
            <li ref={end} className="py-2.5 px-4">
              <Button size="sm" variant="ghost" loading={isFetchingNextPage} onClick={() => void fetchNextPage()}>
                Load more {many}
              </Button>
            </li>
          )}
        </ul>
        <p className="m-0 border-t border-border py-2.5 px-4 text-small text-muted">
          {shown.length >= total
            ? `${total.toLocaleString()} ${total === 1 ? one : many}`
            : `${shown.length.toLocaleString()} of ${total.toLocaleString()} ${many}`}
        </p>
      </div>
      {selected && (
        <Traces
          key={rowKey(selected)}
          row={selected}
          basePath={basePath}
          one={one}
          missingText={missingText}
          dim={dim}
          contextNote={contextNote}
          action={rowAction?.(selected)}
        />
      )}
    </div>,
  );
}

function RowsSkeleton({ label }: { label: string }) {
  return (
    <div className={FRAME} style={FRAME_STYLE} role="status" aria-label={label}>
      <div className="flex flex-col min-h-0 border-r border-border">
        <div className="flex-1 overflow-hidden">
          {[82, 64, 90, 70, 58, 76, 66, 84].map((width, i) => (
            <div key={i} className="border-b border-border py-3 px-4">
              <div className="flex justify-between">
                <Skeleton className="h-3 w-24" />
                <Skeleton className="h-3 w-8" />
              </div>
              <Skeleton className="mt-2 h-3.5" style={{ width: `${width}%` }} />
            </div>
          ))}
        </div>
        <div className="border-t border-border py-3 px-4">
          <Skeleton className="h-3 w-28" />
        </div>
      </div>
      <div className="flex flex-col min-w-0 min-h-0">
        <div className="border-b border-border py-3.5 px-5">
          <Skeleton className="h-3 w-40" />
        </div>
        <div className="flex flex-col gap-3 py-4.5 px-5">
          <Skeleton className="h-3 w-3/5" />
          <Skeleton className="mt-2 h-10 w-1/2" />
          <Skeleton className="h-3.5 w-5/6" />
          <Skeleton className="h-3.5 w-2/3" />
          <Skeleton className="mt-2 h-10 w-1/2" />
        </div>
      </div>
    </div>
  );
}

function emphasisBySpan(row: FlaggedRow, spansByTrace: Map<string, Span[]>): EmphasisBySpan {
  const out: EmphasisBySpan = new Map();
  for (const { traceId, spanId, ...entry } of row.emphasis) {
    const spans = spansByTrace.get(traceId);
    if (!spans) continue;
    const id = entry.part === "step" ? spanId : spanOrder(spans)[0]?.id;
    if (!id) continue;
    out.set(id, [...(out.get(id) ?? []), entry]);
  }
  return out;
}

function Traces({
  row,
  basePath,
  one,
  missingText,
  dim,
  contextNote,
  action,
}: {
  row: FlaggedRow;
  basePath: string;
  one: string;
  missingText: string;
  dim: boolean;
  contextNote?: string;
  action?: ReactNode;
}) {
  const { api } = useTenant();
  const ids = row.contextTraceIds.length > 0 ? row.contextTraceIds : [row.traceId];
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ["trace", api.base, id],
      queryFn: () => api.getTrace(id),
      retry: false,
    })),
  });

  const loading = results.some((r) => r.isLoading);
  const loaded = results.flatMap((r) => (r.data ? [r.data] : []));
  const flaggedMissing = !results[results.length - 1]?.data && !loading;
  const spansByTrace = new Map<string, Span[]>(loaded.map((d) => [d.trace.id, d.spans]));
  const before = ids.length - 1;

  // Scroll this pane (never the page) so the flagged message is last in view, and keep re-aligning as
  // messages fold and fonts settle until the reader touches the pane.
  const pane = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const box = pane.current;
    const inner = content.current;
    if (loading || !box || !inner) return;
    const align = () => {
      const flagged = box.querySelector<HTMLElement>('[data-flagged="true"]');
      if (!flagged) return;
      const below = flagged.getBoundingClientRect().bottom - box.getBoundingClientRect().bottom;
      box.scrollTop = Math.max(0, box.scrollTop + below + FLAGGED_BOTTOM_GAP);
    };
    align();
    const settle = new ResizeObserver(align);
    settle.observe(inner);
    const stop = () => settle.disconnect();
    const handOver = ["wheel", "touchstart", "keydown", "mousedown"] as const;
    for (const e of handOver) box.addEventListener(e, stop, { once: true, passive: true });
    return () => {
      stop();
      for (const e of handOver) box.removeEventListener(e, stop);
    };
  }, [loading]);

  return (
    <div className="flex flex-col min-w-0 min-h-0">
      <div className="flex items-center gap-2.5 border-b border-border py-3 px-5 text-small">
        <span className="font-mono text-muted truncate">{row.id}</span>
        {row.cleared && <span className="text-muted">· Cleared</span>}
        <span className="ml-auto flex shrink-0 items-center gap-3">
          <Link
            to={`${basePath}/traces/${encodeURIComponent(row.traceId)}`}
            className="text-link hover:text-link-hover transition-colors"
          >
            View trace
          </Link>
          {row.sessionId && (
            <Link
              to={`${basePath}/sessions/${encodeURIComponent(row.sessionId)}`}
              className="text-link hover:text-link-hover transition-colors"
            >
              View session
            </Link>
          )}
          {action}
        </span>
      </div>
      {/* No scroll anchoring: the browser's adjustment would undo the alignment above. */}
      <div ref={pane} className="flex-1 overflow-y-auto py-4.5 px-5" style={{ overflowAnchor: "none" }}>
        <div ref={content}>
        <p className="mt-0 mb-3.5 text-small text-muted">
          {row.cleared ? "Cleared" : "Flagged"} with a score of {row.score != null ? row.score.toFixed(2) : "–"}
          {row.flaggedAt ? ` on ${new Date(row.flaggedAt).toLocaleString(undefined, DAY_TIME)}` : ""}.
          {before > 0 ? ` Showing the ${before === 1 ? "turn" : `${before} turns`} before it.` : ""}
        </p>
        {loading ? (
          <p className="m-0 text-small text-muted">Loading {one}...</p>
        ) : flaggedMissing ? (
          <p className="m-0 text-body text-subtle" style={{ maxWidth: 520 }}>
            {missingText}
          </p>
        ) : (
          <SessionConversationView
            traces={loaded.map((d) => d.trace)}
            spansByTrace={spansByTrace}
            focusId={null}
            emphasis={emphasisBySpan(row, spansByTrace)}
            dim={dim}
            contextNote={contextNote}
          />
        )}
        </div>
      </div>
    </div>
  );
}

export function ConversationFilter({
  options,
  value,
  onChange,
  label = "Filter sessions",
}: {
  options: { key: string; label: string }[];
  value: string;
  onChange: (key: string) => void;
  label?: string;
}) {
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-1.5 mb-3">
      {options.map((o) => (
        <Button
          key={o.key}
          size="sm"
          variant={o.key === value ? "secondary" : "ghost"}
          aria-pressed={o.key === value}
          onClick={() => onChange(o.key)}
        >
          {o.label}
        </Button>
      ))}
    </div>
  );
}
