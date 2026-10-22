// SPDX-License-Identifier: Apache-2.0
// Status is runtime only: grader verdicts never color this table.
import { Fragment, memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { Search, ChevronRight } from "lucide-react";
import {
  PageHeader,
  Table,
  TBody,
  TD,
  TH,
  THead,
  TR,
  ErrorNote,
  TableSkeleton,
  Button,
  Spinner,
  cn,
} from "../../ui";
import { useProjectApi, useTenant } from "../../tenant/TenantContext";
import { searchPatch } from "../../lib/searchPatch";
import { classifierHref } from "../traceLinker";
import {
  COLUMN_DEFS,
  ColumnsControl,
  SESSION_EXPAND_COLUMN_WIDTH,
  columnWidths,
  useColumnConfig,
  type ColumnDef,
  type ColumnKey,
} from "./index-columns";
import {
  FacetControl,
  RefreshControl,
  STATUS_OPTIONS,
  TimeRangeControl,
  rangeLabel,
  resolveRange,
  useAutoRefresh,
  useKeptTraceView,
  useTraceQueryState,
  type FacetOption,
} from "./index-filters";
import {
  cellState,
  exactTokens,
  formatCost,
  formatLatency,
  formatWhen,
  useObservedFacets,
  useTracesIndex,
  formatTokens,
  type TraceFilters,
  type TraceListItem,
  filterParams,
} from "./index-data";
import { ANY_DETECTION, DetectionMarker } from "./detection-marker";
import { useSessionsIndex, sessionName, sessionDurationMs, type SessionListItem } from "./session-index-data";
import { TraceRail } from "./TraceRail";
import { useRowWindow } from "./useRowWindow";
import { SessionRail } from "./SessionRail";

const CELL_PAD = { paddingInline: 12, paddingBlock: 9 } as const;

/** Fixed rather than harvested: a v2 trace row carries no span facts, and the typology is closed. */
const KIND_OPTIONS: FacetOption[] = [
  { value: "llm", label: "llm" },
  { value: "tool", label: "tool" },
  { value: "agent", label: "agent" },
  { value: "chain", label: "chain" },
  { value: "retrieval", label: "retrieval" },
  { value: "embedding", label: "embedding" },
  { value: "rerank", label: "rerank" },
  { value: "guardrail", label: "guardrail" },
];

export function TracesIndex() {
  const api = useProjectApi();
  return useKeptTraceView(api.base) ? <TracesList /> : null;
}

function TracesList() {
  const { api, orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const { visible, toggle, reset } = useColumnConfig();

  const [searchParams, setSearchParams] = useSearchParams();
  const patch = searchPatch(searchParams, setSearchParams);
  const { state, setRange, setFacet, clearFacets, setCallSite, setSearch, clearAll, activeCount } =
    useTraceQueryState();
  const { range, facets, callSite, q: submittedQuery } = state;
  // `q` is a server-side filter, so it fires on submit, not per keystroke.
  const [query, setQuery] = useState(submittedQuery);

  // Evicts every fetched page so the feed never splices two windows together.
  const [epoch, setEpoch] = useState(0);
  const refresh = useCallback(() => setEpoch((e) => e + 1), []);
  const { auto, setAuto, paused: autoPaused } = useAutoRefresh(refresh);
  // Cached against the range and epoch so an unrelated re-render cannot hand the query a new `now` and
  // restart paging.
  const anchor = useRef({ key: "", bounds: { from: null as string | null, to: null as string | null } });
  const anchorKey = `${epoch}|${range.kind}|${JSON.stringify(range)}`;
  if (anchor.current.key !== anchorKey) {
    anchor.current = { key: anchorKey, bounds: resolveRange(range, Date.now()) };
  }
  const bounds = anchor.current.bounds;

  const clearAllFilters = () => {
    setQuery("");
    clearAll();
  };

  const columns = useMemo(() => COLUMN_DEFS.filter((c) => visible.has(c.key)), [visible]);

  // Sessions page server-side, so a session's totals are accurate when it renders.
  const groupBySession = searchParams.get("groupBy") === "session";
  const setGroupBySession = (grouped: boolean) => patch({ groupBy: grouped ? "session" : null });
  const tableMinWidth = Math.max(
    760,
    columns.reduce((sum, c) => sum + c.width, 0) + (groupBySession ? SESSION_EXPAND_COLUMN_WIDTH : 0),
  );
  const colWidths = columnWidths(columns, groupBySession ? SESSION_EXPAND_COLUMN_WIDTH : 0);

  // Server-side: the list is keyset-paginated, so a browser filter would only see the loaded page.
  const filters: TraceFilters = {
    q: submittedQuery || undefined,
    callSite: typeof callSite === "object" ? callSite.id : undefined,
    hasCallSite: callSite === "any" ? true : callSite === "none" ? false : undefined,
    status: facets.status ?? undefined,
    kind: facets.kind ?? undefined,
    from: bounds.from,
    to: bounds.to,
    detectedBy: facets.detected_by ?? undefined,
    promptName: facets.prompt ?? undefined,
    promptVersion: facets.prompt ? facets.prompt_version ?? undefined : undefined,
  };
  const q = useTracesIndex(filters, epoch, !groupBySession);
  const sq = useSessionsIndex(filters, epoch, groupBySession);

  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });
  const detectors = (classifiersQ.data ?? []).filter((c) => c.shown_in_trace_views);
  const detectedByOptions = detectors.map((c) => ({ value: c.id, label: c.name }));
  const pickedDetector = detectors.find((c) => c.id === facets.detected_by) ?? null;

  const filtered = activeCount > 0 || !!submittedQuery;
  const narrowed =
    !!facets.status ||
    !!facets.kind ||
    !!facets.detected_by ||
    !!facets.prompt ||
    (callSite !== "any" && callSite !== "all") ||
    !!submittedQuery;
  const rows = useMemo(() => q.data?.pages.flatMap((p) => p.traces) ?? [], [q.data]);
  const sessionRows = useMemo(() => sq.data?.pages.flatMap((p) => p.sessions) ?? [], [sq.data]);

  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const toggleExpanded = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const observed = useObservedFacets(rows);
  const callSiteOptions = useMemo(
    () => observed.callSites.map((c) => ({ value: c, label: c })),
    [observed.callSites],
  );

  const active = groupBySession ? sq : q;
  const { fetchNextPage, hasNextPage, isFetchingNextPage } = active;
  const loadMore = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [fetchNextPage, hasNextPage, isFetchingNextPage]);
  const sentinelRef = useInfiniteScroll(loadMore, hasNextPage && !active.isLoading);
  const displayRows = groupBySession ? sessionRows : rows;

  const openTraceId = searchParams.get("trace");
  const openTrace = (row: TraceListItem) => patch({ trace: row.id });
  // A stable handle, so a page landing re-renders only its own rows rather than every row above it.
  const openTraceRef = useRef(openTrace);
  useLayoutEffect(() => {
    openTraceRef.current = openTrace;
  });
  const onOpenRow = useCallback((row: TraceListItem) => openTraceRef.current(row), []);
  const openTraceById = (traceId: string) => patch({ trace: traceId });
  const closeTrace = () => patch({ trace: null, view: null, span: null });

  const openSessionId = searchParams.get("session");
  const openSession = (row: SessionListItem) => patch({ session: row.id });
  const closeSession = () => patch({ session: null, view: null, span: null });

  return (
    <div className="pt-9 px-10 pb-14">
      <PageHeader
        kicker="Monitor"
        title="Traces"
        actions={
          <>
            <ColumnsControl visible={visible} onToggle={toggle} onReset={reset} />
            <TimeRangeControl range={range} onChange={setRange} />
            <RefreshControl onRefresh={refresh} auto={auto} onAutoChange={setAuto} paused={autoPaused} />
          </>
        }
      />

      <div
        className="flex items-center rounded-control border border-border-strong bg-surface gap-2.5 py-2.25 px-3">
        <Search size={14} strokeWidth={1.5} aria-hidden="true" className="shrink-0 text-subtle" />
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              setSearch(query.trim());
            }
          }}
          aria-label="Filter traces"
          placeholder="Filter traces by name, session, or content"
          className="min-w-0 flex-1 bg-transparent text-fg outline-none placeholder:text-subtle text-body"
          
        />
        <span className="shrink-0 font-mono text-subtle text-label">
          ⏎ to search
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-1.5 mt-3">
        <FacetControl
          label="Status"
          value={facets.status}
          options={STATUS_OPTIONS}
          onChange={(v) => setFacet("status", v)}
        />
        <FacetControl
          label="Kind"
          value={facets.kind}
          options={KIND_OPTIONS}
          onChange={(v) => setFacet("kind", v)}
        />
        <FacetControl
          label="Call site"
          value={facets.call_site}
          options={callSiteOptions}
          onChange={(v) => setCallSite(v ? { id: v } : "any")}
          head={[
            { label: "All traces", short: "all", selected: callSite === "all", onSelect: () => setCallSite("all") },
            { label: "Any call site", selected: callSite === "any", onSelect: () => setCallSite("any") },
          ]}
          foot={[
            { label: "No call site", short: "none", selected: callSite === "none", onSelect: () => setCallSite("none") },
          ]}
          emptyHint="No call site has been seen in the loaded traces yet."
        />
        <FacetControl
          label="Detected by"
          value={facets.detected_by === ANY_DETECTION ? null : facets.detected_by}
          options={detectedByOptions}
          onChange={(v) => setFacet("detected_by", v)}
          head={[
            { label: "All traces", selected: !facets.detected_by, onSelect: () => setFacet("detected_by", null) },
            {
              label: "Any detection",
              short: "any",
              selected: facets.detected_by === ANY_DETECTION,
              onSelect: () => setFacet("detected_by", ANY_DETECTION),
            },
          ]}
          emptyHint="No classifier that marks traces is set up yet."
        />
        <button
          type="button"
          role="switch"
          aria-checked={groupBySession}
          onClick={() => setGroupBySession(!groupBySession)}
          className="ml-auto inline-flex h-8 items-center rounded-control border border-border bg-surface transition-colors hover:border-border-strong gap-2.25 py-0 px-2.5"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <span className="text-muted text-small">
            Group by session
          </span>
          <span
            aria-hidden="true"
            className={cn(
              "relative inline-flex h-[19px] w-[34px] shrink-0 items-center rounded-pill transition-colors",
              groupBySession ? "bg-accent" : "bg-border-strong",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            <span
              className={cn(
                "pointer-events-none absolute top-[2px] block size-[15px] rounded-pill transition-[left]",
                groupBySession ? "left-[17px] bg-accent-text-on" : "left-[2px] bg-fg",
              )}
              style={{ transitionDuration: "var(--duration-micro)" }}
            />
          </span>
        </button>
      </div>

      {filtered && (
        <div className="flex flex-wrap items-center gap-1.5 mt-3 mx-0 mb-0">
          {callSite !== "any" && (
            <FilterChip
              label={`call site: ${typeof callSite === "object" ? callSite.id : callSite}`}
              onRemove={() => setCallSite("any")}
            />
          )}
          {facets.prompt && (
            <FilterChip
              label={`prompt: ${facets.prompt}${versionSuffix(facets.prompt_version)}`}
              onRemove={() => clearFacets(["prompt", "prompt_version"])}
            />
          )}
          {submittedQuery && (
            <FilterChip label={`search: ${submittedQuery}`} onRemove={() => setSearch("")} />
          )}
          {filtered && (
            <button
              type="button"
              onClick={clearAllFilters}
              className="text-subtle transition-colors hover:text-fg cursor-pointer ml-0.5 text-small">
              Clear all
            </button>
          )}
        </div>
      )}

      <div style={{ height: 14 }} />

      {active.isLoading ? (
        <TableSkeleton rows={6} cols={columns.length} />
      ) : active.isError ? (
        <ErrorNote error={active.error} />
      ) : displayRows.length === 0 ? (
        <NothingHere
          filtered={narrowed}
          allTime={range.kind === "all"}
          range={rangeLabel(range).toLowerCase()}
          onClearAll={clearAllFilters}
          onSearchAllTime={() => setRange({ kind: "all" })}
          onShowAll={callSite === "any" ? () => setCallSite("all") : undefined}
          offClassifier={
            pickedDetector && !pickedDetector.enabled
              ? {
                  name: pickedDetector.name,
                  href: classifierHref(pickedDetector, basePath),
                }
              : undefined
          }
        />
      ) : (
        // Fixed layout: a column's width must never depend on its content, or every flat/grouped toggle resizes
        // the table. `columnWidths` hands any surplus to the text columns.
        <Table className="text-body" style={{ tableLayout: "fixed", minWidth: tableMinWidth }}>
          <colgroup>
            {columns.map((col, i) => (
              <col key={col.key} style={{ width: colWidths[i] }} />
            ))}
            {groupBySession && <col style={{ width: SESSION_EXPAND_COLUMN_WIDTH }} />}
          </colgroup>
          <THead>
            <TR>
              {columns.map((col) => (
                <TH
                  key={col.key}
                  className={col.numeric ? "text-right" : undefined}
                >
                  {col.label}
                </TH>
              ))}
              {groupBySession && <TH className="bg-surface" style={STICKY_TH_STYLE} aria-hidden="true" />}
            </TR>
          </THead>
          {groupBySession ? (
            <TBody>
              {sessionRows.map((s) => {
                const isOpen = expanded.has(s.id);
                return (
                  <SessionGroupRows
                    key={s.id}
                    session={s}
                    columns={columns}
                    expanded={isOpen}
                    onToggle={() => toggleExpanded(s.id)}
                    onOpenSession={() => openSession(s)}
                    onOpenTrace={openTraceById}
                    filters={filters}
                  />
                );
              })}
            </TBody>
          ) : (
            <TraceRows rows={rows} columns={columns} onOpen={onOpenRow} />
          )}
        </Table>
      )}

      {/* The button is the keyboard and screen-reader path to the same action: you cannot tab into an intersection. */}
      {displayRows.length > 0 && hasNextPage && (
        <div
          ref={sentinelRef}
          className="flex items-center justify-center pt-5 px-0 pb-2">
          {isFetchingNextPage ? (
            <span className="flex items-center text-muted gap-2 text-small">
              <Spinner size="sm" /> {groupBySession ? "Loading older sessions…" : "Loading older traces…"}
            </span>
          ) : (
            <Button variant="ghost" size="sm" onClick={loadMore}>
              {groupBySession ? "Load older sessions" : "Load older traces"}
            </Button>
          )}
        </div>
      )}
      {displayRows.length > 0 && !hasNextPage && (
        <p className="text-center text-subtle pt-5 px-0 pb-2 text-small">
          {groupBySession ? "No older sessions." : "No older traces."}
        </p>
      )}

      <TraceRail traceId={openTraceId} onClose={closeTrace} />
      <SessionRail sessionId={openSessionId} onClose={closeSession} />
    </div>
  );
}

/**
 * A gradient in the cell's own background, not a box-shadow, which sticky positioning and border-collapse
 * do not reliably paint. `paddingBlock` matches the other cells so text sets the row height.
 */
const STICKY_TD_STYLE: CSSProperties = {
  position: "sticky",
  right: 0,
  width: SESSION_EXPAND_COLUMN_WIDTH,
  paddingBlock: CELL_PAD.paddingBlock,
  backgroundImage: "linear-gradient(to right, var(--color-scrim), transparent 20px)",
  backgroundRepeat: "no-repeat",
};
const STICKY_TH_STYLE: CSSProperties = { ...STICKY_TD_STYLE, paddingInline: 0, backgroundImage: "none" };

function SessionGroupRows({
  session,
  columns,
  expanded,
  onToggle,
  onOpenSession,
  onOpenTrace,
  filters,
}: {
  session: SessionListItem;
  columns: ColumnDef[];
  expanded: boolean;
  onToggle: () => void;
  onOpenSession: () => void;
  onOpenTrace: (traceId: string) => void;
  filters: TraceFilters;
}) {
  const api = useProjectApi();
  const detail = useQuery({
    queryKey: ["session-detail-expand", api.base, session.id, filters],
    queryFn: () => api.getSession(session.id, filterParams(filters)),
    // A search the server stopped at its timeout would only run as long again.
    retry: false,
    enabled: expanded,
  });
  const matched = detail.data?.matched_trace_ids ? new Set(detail.data.matched_trace_ids) : null;

  return (
    <>
      <TR
        interactive
        tabIndex={0}
        className="group"
        onClick={onOpenSession}
        onKeyDown={(e) => e.key === "Enter" && onOpenSession()}
      >
        {columns.map((col) => (
          <SessionCell key={col.key} col={col.key} row={session} />
        ))}
        <TD className="group/chev bg-bg group-hover:bg-hover" style={STICKY_TD_STYLE}>
          <button
            type="button"
            aria-label={expanded ? "Collapse session" : "Expand session"}
            aria-expanded={expanded}
            onClick={(e) => {
              e.stopPropagation();
              onToggle();
            }}
            className="flex items-center justify-center text-subtle transition-colors group-hover/chev:text-fg"
            style={{ width: 18, height: 18, transitionDuration: "var(--duration-micro)" }}
          >
            <ChevronRight
              size={12}
              strokeWidth={1.75}
              className={cn("transition-transform", expanded && "rotate-90")}
              style={{ filter: "drop-shadow(0 1px 2px var(--color-scrim))" }}
            />
          </button>
        </TD>
      </TR>
      {expanded && detail.isLoading && (
        <TR>
          <TD colSpan={columns.length + 1} className="text-subtle pl-9 text-small" style={{ ...CELL_PAD }}>
            <span className="flex items-center gap-2">
              <Spinner size="sm" /> Loading traces…
            </span>
          </TD>
        </TR>
      )}
      {expanded && detail.isError && (
        <TR>
          <TD colSpan={columns.length + 1} className="text-subtle pl-9 text-small" style={{ ...CELL_PAD }}>
            This session&rsquo;s traces could not be loaded. Try again.
          </TD>
        </TR>
      )}
      {expanded &&
        detail.data?.traces.map((t) => (
          <TR
            key={t.id}
            interactive
            tabIndex={0}
            className={cn("group bg-raised", matched && !matched.has(t.id) && "opacity-50")}
            onClick={() => onOpenTrace(t.id)}
            onKeyDown={(e) => {
              if (e.key === "Enter") onOpenTrace(t.id);
            }}
          >
            {columns.map((col) => (
              <Cell key={col.key} col={col.key} row={t} outside={!!matched && !matched.has(t.id)} />
            ))}
            <TD
              className="bg-raised group-hover:bg-hover"
              style={{ ...STICKY_TD_STYLE, backgroundImage: "none" }}
            />
          </TR>
        ))}
    </>
  );
}

/** A rollup: per-turn facts a session has many of read as a dash. */
function SessionCell({ col, row }: { col: ColumnKey; row: SessionListItem }) {
  switch (col) {
    case "when":
      return <Text>{formatWhen(row.started_at)}</Text>;
    case "endedAt":
      return <Text>{formatWhen(row.last_activity_at)}</Text>;
    case "name":
      return (
        <TD className="font-mono text-fg text-small" style={{ ...CELL_PAD }}>
          <span className="flex items-center gap-1.75">
            <span
              aria-hidden="true"
              className={cn("shrink-0 rounded-pill", (row.error_count ?? 0) > 0 ? "bg-error" : "bg-transparent")}
              style={{ width: 6, height: 6 }}
            />
            {(row.error_count ?? 0) > 0 && <span className="sr-only">errored: </span>}
            <span className="min-w-0 truncate">{sessionName(row)}</span>
            {(row.unsettled_traces ?? 0) > 0 && (
              <span
                className="shrink-0 text-subtle text-label"
                
                title="Traces are still arriving"
              >
                live
              </span>
            )}
          </span>
        </TD>
      );
    // The session bracketed: the first trace's input and the last trace's output, as the server sent them.
    case "detectedBy":
      return <Detected names={row.detected_by.map((d) => d.name)} />;
    case "input":
      return <Preview value={row.first_input_preview} />;
    case "output":
      return <Preview value={row.last_output_preview} />;
    case "traceId":
      return <Text>—</Text>;
    case "latency":
      // Wall clock from the first trace's start to the latest activity, not a sum: traces overlap or leave
      // gaps, and a session can span days.
      return <Num>{formatLatency(sessionDurationMs(row))}</Num>;
    case "cost":
      return <Num>{row.total_cost != null ? formatCost(row.total_cost) : "—"}</Num>;
    case "costIn":
      return <Num>{row.input_cost != null ? formatCost(row.input_cost) : "—"}</Num>;
    case "costOut":
      return <Num>{row.output_cost != null ? formatCost(row.output_cost) : "—"}</Num>;
    case "spans":
      return <Num>{row.span_count != null ? String(row.span_count) : "—"}</Num>;
    case "errors":
      return <Num>{row.error_count != null ? String(row.error_count) : "—"}</Num>;
    case "tokensIn":
      return <Num title={exactTokens(row.input_tokens)}>{formatTokens(row.input_tokens)}</Num>;
    case "tokensOut":
      return <Num title={exactTokens(row.output_tokens)}>{formatTokens(row.output_tokens)}</Num>;
    case "cacheRead":
      return <Num title={exactTokens(row.cache_read_tokens)}>{formatTokens(row.cache_read_tokens)}</Num>;
    case "cacheWrite":
      return <Num title={exactTokens(row.cache_write_tokens)}>{formatTokens(row.cache_write_tokens)}</Num>;
    case "reasoning":
      return <Num title={exactTokens(row.reasoning_tokens)}>{formatTokens(row.reasoning_tokens)}</Num>;
    case "tokens":
      return <Num title={exactTokens(row.total_tokens)}>{formatTokens(row.total_tokens)}</Num>;
    case "callSite":
      // Same value as Name, so the two columns never disagree.
      return <Text>{row.dominant_call_site_id ? sessionName(row) : "—"}</Text>;
    case "session":
      return <Text>{row.id}</Text>;
  }
}

/** `rootMargin` fires a screen early so rows are in place before the reader arrives. */
function useInfiniteScroll(onHit: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = ref.current;
    if (!node || !enabled) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) onHit();
      },
      { rootMargin: "600px 0px" },
    );
    io.observe(node);
    return () => io.disconnect();
  }, [onHit, enabled]);
  return ref;
}

/** ` v3` for a version number, ` (prod)` for a label a producer sent, nothing when there is no version. */
function versionSuffix(version: string | null): string {
  if (!version) return "";
  return /^\d+$/.test(version) ? ` v${version}` : ` (${version})`;
}

function FilterChip({ label, onRemove }: { label: string; onRemove: () => void }) {
  return (
    <span
      className="inline-flex items-center rounded-pill border border-border-strong bg-surface font-mono text-fg gap-1.5 py-0.75 px-2.25 text-label">
      {label}
      <button
        type="button"
        aria-label={`Remove filter ${label}`}
        className="text-subtle transition-colors hover:text-fg"
        onClick={onRemove}
      >
        ×
      </button>
    </span>
  );
}

/** `title` carries the unrounded value for the abbreviating columns. */
function Num({ children, title }: { children: React.ReactNode; title?: string }) {
  return (
    <TD
      className="whitespace-nowrap text-right font-mono text-fg text-small"
      style={{ ...CELL_PAD, fontVariantNumeric: "tabular-nums" }}
    >
      {title ? <span title={title}>{children}</span> : children}
    </TD>
  );
}

function Text({ children }: { children: React.ReactNode }) {
  return (
    <TD className="truncate font-mono text-fg text-small" style={{ ...CELL_PAD }}>
      {children}
    </TD>
  );
}

function Preview({ value }: { value: string | null | undefined }) {
  const text = (value ?? "").trim();
  if (!text) return <Text>—</Text>;
  return (
    <TD className="truncate text-fg text-small" style={{ ...CELL_PAD }} title={text}>
      {text}
    </TD>
  );
}

/** `—` means settled and nothing; an unsettled trace shows `…`, because its total is not yet computed. */
function Rollup({
  row,
  value,
  render,
  title,
}: {
  row: TraceListItem;
  value: number | null | undefined;
  render: (v: number | null | undefined) => string;
  title?: string;
}) {
  const state = cellState(row, value);
  if (state === "pending") {
    return (
      <Num title="Still arriving. This trace has not rolled up yet.">
        <span className="text-subtle">…</span>
      </Num>
    );
  }
  return <Num title={title}>{render(value)}</Num>;
}

function Detected({ names }: { names: string[] }) {
  return (
    <TD className="truncate text-small" style={{ ...CELL_PAD }}>
      <DetectionMarker names={names} size="small" />
    </TD>
  );
}

/** The flat list's body, windowed once it is long: see {@link useRowWindow}. */
function TraceRows({
  rows,
  columns,
  onOpen,
}: {
  rows: TraceListItem[];
  columns: ColumnDef[];
  onOpen: (row: TraceListItem) => void;
}) {
  const { bodyRef, win } = useRowWindow(rows.length);
  return (
    <TBody ref={bodyRef}>
      {win.top > 0 && <SpacerRow height={win.top} span={columns.length} />}
      {rows.slice(win.start, win.end).map((row) => (
        <TraceRow key={row.id} row={row} columns={columns} onOpen={onOpen} />
      ))}
      {win.bottom > 0 && <SpacerRow height={win.bottom} span={columns.length} />}
    </TBody>
  );
}

function SpacerRow({ height, span }: { height: number; span: number }) {
  return (
    <tr aria-hidden="true">
      <td colSpan={span} style={{ height, padding: 0 }} />
    </tr>
  );
}

const TraceRow = memo(function TraceRow({
  row,
  columns,
  onOpen,
}: {
  row: TraceListItem;
  columns: ColumnDef[];
  onOpen: (row: TraceListItem) => void;
}) {
  return (
    <TR
      data-row
      interactive
      tabIndex={0}
      onClick={() => onOpen(row)}
      onKeyDown={(e) => {
        if (e.key === "Enter") onOpen(row);
      }}
    >
      {columns.map((col) => (
        <Cell key={col.key} col={col.key} row={row} />
      ))}
    </TR>
  );
});

function Cell({ col, row, outside = false }: { col: ColumnKey; row: TraceListItem; outside?: boolean }) {
  switch (col) {
    case "when":
      return <Text>{formatWhen(row.started_at)}</Text>;
    case "endedAt":
      return <Text>{formatWhen(row.ended_at)}</Text>;
    case "name":
      // Hidden from assistive tech only because the adjacent sr-only text says the same in words.
      return (
        <TD className="font-mono text-fg text-small" style={{ ...CELL_PAD }}>
          <span className="flex items-center gap-1.75">
            <span
              aria-hidden="true"
              className={cn("shrink-0 rounded-pill", row.status === "error" ? "bg-error" : "bg-transparent")}
              style={{ width: 6, height: 6 }}
            />
            {row.status === "error" && <span className="sr-only">errored: </span>}
            {outside && <span className="sr-only">outside the filters: </span>}
            <span className="min-w-0 truncate">{row.name ?? row.id}</span>
            {!row.is_settled && (
              <span className="shrink-0 text-subtle text-label"  title="Spans are still arriving">
                live
              </span>
            )}
          </span>
        </TD>
      );
    case "input":
      return <Preview value={row.input_preview} />;
    case "output":
      return <Preview value={row.output_preview} />;
    case "detectedBy":
      return <Detected names={row.detected_by.map((d) => d.name)} />;
    case "session":
      return <Text>{row.session ?? "—"}</Text>;
    case "traceId":
      return <Text>{row.id}</Text>;
    case "callSite":
      return <Text>{row.call_site_id ?? "—"}</Text>;
    case "latency":
      return <Num>{formatLatency(row.latency_ms)}</Num>;
    case "cost":
      return <Rollup row={row} value={row.total_cost} render={formatCost} />;
    case "costIn":
      return <Rollup row={row} value={row.input_cost} render={formatCost} />;
    case "costOut":
      return <Rollup row={row} value={row.output_cost} render={formatCost} />;
    case "spans":
      return <Rollup row={row} value={row.span_count} render={(v) => (v == null ? "—" : String(v))} />;
    case "errors":
      return <Rollup row={row} value={row.error_count} render={(v) => (v == null ? "—" : String(v))} />;
    case "tokensIn":
      return (
        <Rollup row={row} value={row.input_tokens} render={formatTokens} title={exactTokens(row.input_tokens)} />
      );
    case "tokensOut":
      return (
        <Rollup row={row} value={row.output_tokens} render={formatTokens} title={exactTokens(row.output_tokens)} />
      );
    case "cacheRead":
      return (
        <Rollup
          row={row}
          value={row.cache_read_tokens}
          render={formatTokens}
          title={exactTokens(row.cache_read_tokens)}
        />
      );
    case "cacheWrite":
      return (
        <Rollup
          row={row}
          value={row.cache_write_tokens}
          render={formatTokens}
          title={exactTokens(row.cache_write_tokens)}
        />
      );
    case "reasoning":
      return (
        <Rollup
          row={row}
          value={row.reasoning_tokens}
          render={formatTokens}
          title={exactTokens(row.reasoning_tokens)}
        />
      );
    case "tokens":
      return (
        <Rollup row={row} value={row.total_tokens} render={formatTokens} title={exactTokens(row.total_tokens)} />
      );
  }
}

/**
 * A filter matching nothing and a project with no traces look identical from here. Each way out shows
 * only when it would change the result. The default hides untagged traces, so `onShowAll` is its own way out.
 */
function NothingHere({
  filtered,
  allTime,
  range,
  onClearAll,
  onSearchAllTime,
  onShowAll,
  offClassifier,
}: {
  filtered: boolean;
  allTime: boolean;
  range: string;
  onClearAll: () => void;
  onSearchAllTime: () => void;
  onShowAll?: () => void;
  offClassifier?: { name: string; href: string };
}) {
  if (offClassifier) {
    return (
      <div className="text-center pt-18 px-0 pb-10">
        <p className="text-h1 mx-auto text-fg" style={{ maxWidth: 560 }}>
          {offClassifier.name} is off
        </p>
        <p className="mx-auto text-muted mt-3 text-body" style={{ maxWidth: 460, textWrap: "pretty" }}>
          It has no detections to filter by. Turn it on to start scoring.
        </p>
        <p className="text-muted mt-3 text-body">
          <Link to={offClassifier.href} className="text-accent hover:underline">
            Open {offClassifier.name}
          </Link>
          <span className="text-subtle"> · </span>
          <button type="button" onClick={onClearAll} className="text-accent hover:underline cursor-pointer">
            Clear all filters
          </button>
        </p>
      </div>
    );
  }
  const ways = [
    !allTime && { label: "Search all time", onClick: onSearchAllTime },
    onShowAll && { label: "Show all traces", onClick: onShowAll },
    filtered && { label: "Clear all filters", onClick: onClearAll },
  ].filter((w): w is { label: string; onClick: () => void } => !!w);

  return (
    <div className="text-center pt-18 px-0 pb-10">
      <p className="text-h1 mx-auto text-fg" style={{ maxWidth: 560 }}>
        {filtered
          ? "No traces match these filters"
          : onShowAll
            ? allTime
              ? "No traces with a call site yet"
              : "No traces with a call site in this range"
            : allTime
              ? "No traces yet"
              : "No traces in this range"}
      </p>

      {ways.length > 0 && (
        <p className="text-muted mt-3 text-body">
          {ways.map((w, i) => (
            <Fragment key={w.label}>
              {i > 0 && <span className="text-subtle"> · </span>}
              <button type="button" onClick={w.onClick} className="text-accent hover:underline cursor-pointer">
                {w.label}
              </button>
            </Fragment>
          ))}
        </p>
      )}

      {!filtered && !onShowAll && (
        <p
          className="mx-auto text-subtle mt-4 text-small"
          style={{ maxWidth: 460, textWrap: "pretty" }}
        >
          No traces have arrived in {range}. The first one lands here the next time your agent runs. If
          you have not connected a source yet, start under Sources in Settings.
        </p>
      )}
    </div>
  );
}
