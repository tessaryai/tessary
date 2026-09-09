// SPDX-License-Identifier: Apache-2.0
/*
 * Traces index (sheets/traces.md, index sections).
 *
 * Conforming trace table — When · Name · Call site · Input · Output · Status ·
 * Latency · Cost (Verdict opt-in behind Columns, uncolored). Status is RUNTIME
 * ONLY: `ok` as plain muted text, `error` as the errored StatusPill — grader
 * verdicts never color this table. Row click → ?trace=<id>, a rail floating
 * over this same page (not a route change) so the table stays visible behind it.
 */
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
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
import { useProjectApi } from "../../tenant/TenantContext";
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
  type TraceListItem,
} from "./index-data";
import { useSessionsIndex, sessionName, sessionDurationMs, type SessionListItem } from "./session-index-data";
import { TraceRail } from "./TraceRail";
import { SessionRail } from "./SessionRail";

const CELL_PAD = { paddingInline: 12, paddingBlock: 9 } as const;

/**
 * The span typology, fixed rather than harvested.
 *
 * Kinds used to be collected from the rows on screen, which the v2 list cannot do: a trace row carries
 * no span facts. It does not need to — the typology is closed and known, so offering it whole is both
 * cheaper and more useful than offering only what happened to have loaded.
 */
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
  const { visible, toggle, reset } = useColumnConfig();
  const [query, setQuery] = useState("");
  // Submitted separately from the typed value: `q` is a server-side filter, so
  // firing it per keystroke would be a query per character.
  const [submittedQuery, setSubmittedQuery] = useState("");
  // Deep-link filters: Vitals rows land here with ?call_site=<slug>, Classifiers'
  // "Raw detections live in Traces" with ?events=classifier. Chips mirror the URL;
  // removing a chip removes its param.
  const [searchParams, setSearchParams] = useSearchParams();
  const { state, setRange, setFacet, clearAll, activeCount } = useTraceQueryState();
  const { range, facets } = state;

  // Bumped by ↻ and by each auto-refresh tick. Re-resolves a rolling range
  // against a fresh `now` and evicts every page already fetched, so the feed
  // never splices two windows together.
  const [epoch, setEpoch] = useState(0);
  const refresh = useCallback(() => setEpoch((e) => e + 1), []);
  const { auto, setAuto, paused: autoPaused } = useAutoRefresh(refresh);
  // The bounds are pinned, and re-pinned only when the range or the epoch
  // changes — cached against that key rather than recomputed, so an unrelated
  // re-render cannot hand the query a new `now` (and a new query key) and
  // restart paging from the top.
  const anchor = useRef({ key: "", bounds: { from: null as string | null, to: null as string | null } });
  const anchorKey = `${epoch}|${range.kind}|${JSON.stringify(range)}`;
  if (anchor.current.key !== anchorKey) {
    anchor.current = { key: anchorKey, bounds: resolveRange(range, Date.now()) };
  }
  const bounds = anchor.current.bounds;

  const clearAllFilters = () => {
    setQuery("");
    setSubmittedQuery("");
    clearAll();
  };

  const columns = COLUMN_DEFS.filter((c) => visible.has(c.key));

  // Flat vs grouped-by-session — a view mode, not a filter, so it lives in its own param rather than
  // inside useTraceQueryState. Session grouping pages through sessions server-side (see
  // session-index-data.ts) rather than grouping the client's already-loaded trace page, so a session's
  // totals are accurate the moment it renders, not just for whatever traces happen to be on screen.
  const groupBySession = searchParams.get("groupBy") === "session";
  const setGroupBySession = (grouped: boolean) => {
    const next = new URLSearchParams(searchParams);
    if (grouped) next.set("groupBy", "session");
    else next.delete("groupBy");
    setSearchParams(next);
  };
  // The floor the table never goes below — below it the wrapper scrolls rather than the columns
  // shrinking. Above it the table fills the page and `columnWidths` decides where the surplus goes.
  const tableMinWidth = Math.max(
    760,
    columns.reduce((sum, c) => sum + c.width, 0) + (groupBySession ? SESSION_EXPAND_COLUMN_WIDTH : 0),
  );
  const colWidths = columnWidths(columns, groupBySession ? SESSION_EXPAND_COLUMN_WIDTH : 0);

  // Filtering happens server-side — the list is keyset-paginated, so narrowing
  // it in the browser would only ever filter the page that happens to be loaded.
  const q = useTracesIndex(
    {
      q: submittedQuery || undefined,
      callSite: facets.call_site ?? undefined,
      status: facets.status ?? undefined,
      model: facets.model ?? undefined,
      kind: facets.kind ?? undefined,
      from: bounds.from,
      to: bounds.to,
    },
    epoch,
    !groupBySession,
  );
  const sq = useSessionsIndex(epoch, groupBySession);

  const filtered = activeCount > 0 || !!submittedQuery;
  const rows = useMemo(() => q.data?.pages.flatMap((p) => p.traces) ?? [], [q.data]);
  const sessionRows = useMemo(() => sq.data?.pages.flatMap((p) => p.sessions) ?? [], [sq.data]);

  // Which session rows are expanded in place — local, not URL: matches the mock's no-navigation
  // expand, and a reload defaulting back to collapsed is the right behavior for a table's scroll state.
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set());
  const toggleExpanded = (id: string) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  // Filter vocabulary: kinds are a fixed span typology; call sites are whatever the rows have
  // shown so far.
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
  const openTrace = (row: TraceListItem) => {
    const next = new URLSearchParams(searchParams);
    next.set("trace", row.id);
    setSearchParams(next);
  };
  const openTraceById = (traceId: string) => {
    const next = new URLSearchParams(searchParams);
    next.set("trace", traceId);
    setSearchParams(next);
  };
  const closeTrace = () => {
    const next = new URLSearchParams(searchParams);
    next.delete("trace");
    next.delete("view");
    next.delete("span");
    next.delete("verdicts");
    setSearchParams(next);
  };

  const openSessionId = searchParams.get("session");
  const openSession = (row: SessionListItem) => {
    const next = new URLSearchParams(searchParams);
    next.set("session", row.id);
    setSearchParams(next);
  };
  const closeSession = () => {
    const next = new URLSearchParams(searchParams);
    next.delete("session");
    next.delete("view");
    next.delete("span");
    setSearchParams(next);
  };

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

      {/* Search with NL hint — the hint is visual only; ⏎ interprets nothing yet. */}
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
              setSubmittedQuery(query.trim());
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

      {/* Facet controls. Each writes its own URL param, so the view is linkable. */}
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
          onChange={(v) => setFacet("call_site", v)}
          emptyHint="No call site has been seen in the loaded traces yet."
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

      {/*
        Chips only, and only when there are any — the row collapses rather than
        holding open a band of empty space. Deep links (Vitals → call site) show
        up here too. No running count: the range and the filters are already
        stated by the controls above, and the footer says when the list ends.
      */}
      {(facets.call_site || submittedQuery || filtered) && (
        <div className="flex flex-wrap items-center gap-1.5 mt-3 mx-0 mb-0">
          {facets.call_site && (
            <FilterChip label={`call site: ${facets.call_site}`} onRemove={() => setFacet("call_site", null)} />
          )}
          {/* Model has no dropdown — the vocabulary is a span fact the list no longer reads — but a deep
              link can still scope to one, so it needs a way back out. */}
          {facets.model && (
            <FilterChip label={`model: ${facets.model}`} onRemove={() => setFacet("model", null)} />
          )}
          {submittedQuery && (
            <FilterChip label={`search: ${submittedQuery}`} onRemove={() => setSubmittedQuery("")} />
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
        // One empty state, whether a filter emptied the list or nothing has ever
        // arrived — never a bare header-only table. Both ways out are offered
        // here, and each is hidden when it would do nothing.
        <NothingHere
          filtered={filtered}
          allTime={range.kind === "all"}
          range={rangeLabel(range).toLowerCase()}
          onClearAll={clearAllFilters}
          onSearchAllTime={() => setRange({ kind: "all" })}
        />
      ) : (
        // table-layout: fixed against the <colgroup> below, not auto: a column's width must never
        // depend on what's currently in it. Under auto layout, Input/Output swinging between a long
        // preview (trace rows) and a bare "—" (session rollup rows) resized the whole table on every
        // flat/grouped toggle and every session expand/collapse — see COLUMN_DEFS's width doc comment.
        // The table itself stays `w-full` (from Table) so a monitor wider than the column sum is
        // used rather than left blank; `columnWidths` hands that surplus to the text columns.
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
          <TBody>
            {groupBySession
              ? sessionRows.map((s) => {
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
                    />
                  );
                })
              : rows.map((row) => (
                  <TR
                    key={row.id}
                    interactive
                    tabIndex={0}
                    onClick={() => openTrace(row)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") openTrace(row);
                    }}
                  >
                    {columns.map((col) => (
                      <Cell key={col.key} col={col.key} row={row} />
                    ))}
                  </TR>
                ))}
          </TBody>
        </Table>
      )}

      {/*
        The scroll sentinel. It sits below the table and pulls the next page when
        it comes into view; the button is not a fallback for a broken observer but
        the keyboard and screen-reader path to the same action, since you cannot
        tab your way into an intersection.
      */}
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
 * The pinned column reads as floating above the row rather than boxed off with a border: the "shadow"
 * is a dark-to-transparent gradient baked into the cell's own background, not a `box-shadow` cast onto
 * its neighbor — sticky positioning and border-collapse fight over paint order, so a box-shadow here
 * doesn't reliably render, while a background-image painted inside the cell's own box always does. The
 * background COLOR (which row-state — default/hover/nested — this cell is currently part of) is set by
 * the caller via className, layered underneath this same gradient. `paddingBlock` is pinned to the
 * other cells' own (rather than the default `--density-cell-py`) so a row's height is set by its text
 * line-height everywhere, not by whichever cell happens to be tallest — the chevron button below is
 * sized to match for the same reason.
 */
const STICKY_TD_STYLE: CSSProperties = {
  position: "sticky",
  right: 0,
  width: SESSION_EXPAND_COLUMN_WIDTH,
  paddingBlock: CELL_PAD.paddingBlock,
  backgroundImage: "linear-gradient(to right, var(--color-scrim), transparent 20px)",
  backgroundRepeat: "no-repeat",
};
/** The header's own cell needs no shadow — nothing scrolls beneath a header — just its row's own fill. */
const STICKY_TH_STYLE: CSSProperties = { ...STICKY_TD_STYLE, paddingInline: 0, backgroundImage: "none" };

/**
 * One session, as two things: its own row (rollups, chevron), and — only once expanded — its traces,
 * fetched on demand rather than carried by the list page. `getSession` is already the read the rail
 * will reuse in step 4; fetching it here for expand-in-place means there is no second, cheaper "just the
 * trace list" read to keep in sync with it.
 */
function SessionGroupRows({
  session,
  columns,
  expanded,
  onToggle,
  onOpenSession,
  onOpenTrace,
}: {
  session: SessionListItem;
  columns: ColumnDef[];
  expanded: boolean;
  onToggle: () => void;
  onOpenSession: () => void;
  onOpenTrace: (traceId: string) => void;
}) {
  const api = useProjectApi();
  const detail = useQuery({
    queryKey: ["session-detail-expand", api.base, session.id],
    queryFn: () => api.getSession(session.id),
    enabled: expanded,
  });

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
            className="group bg-raised"
            onClick={() => onOpenTrace(t.id)}
            onKeyDown={(e) => {
              if (e.key === "Enter") onOpenTrace(t.id);
            }}
          >
            {columns.map((col) => (
              <Cell key={col.key} col={col.key} row={t} />
            ))}
            {/* No shadow/chevron here — the pinned treatment is a session-level affordance; a nested
                trace row has nothing to expand, so its slice of the column just continues the row's
                own background (hover included) rather than drawing a second "floating" cell under it. */}
            <TD
              className="bg-raised group-hover:bg-hover"
              style={{ ...STICKY_TD_STYLE, backgroundImage: "none" }}
            />
          </TR>
        ))}
    </>
  );
}

/** A session row's cells — a rollup of its traces, not a trace itself: Input/Output/Latency are per-turn
 * facts a session doesn't have one of, so they read as a dash rather than an average or a first/last value. */
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
    // Not an aggregate — the session bracketed as one interaction, the same way a book review might
    // quote the opening line and the ending rather than "averaging" the whole text. Only the first
    // trace's own input and the last trace's own output, exactly as the server sent them.
    case "input":
      return <Preview value={row.first_input_preview} />;
    case "output":
      return <Preview value={row.last_output_preview} />;
    // A per-trace identifier — a session has many, so there is no one value to show.
    case "traceId":
      return <Text>—</Text>;
    case "latency":
      // The same first-input/last-output bracket, timed: not a sum of trace latencies (traces can
      // overlap or leave gaps), but the wall-clock span from the first trace starting to the session's
      // most recent activity. Reads very differently from a trace's own latency for a long-running
      // session (this can be a real multi-day span) — that's the actual shape of the data, not a bug.
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
      // Same value and formatting as Name — the dominant call site, +N when more than one touched the
      // session. A bare dominant_call_site_id here would look like a single definitive answer where
      // Name is honest about a session having touched several; the two columns should never disagree.
      return <Text>{row.dominant_call_site_id ? sessionName(row) : "—"}</Text>;
    case "session":
      return <Text>{row.id}</Text>;
    case "sessionExpand":
      return null;
  }
}

/**
 * Pull the next page when the sentinel scrolls into view.
 *
 * `rootMargin` fires it a screen early so the rows are already in the table by
 * the time the reader gets there — an infinite list that visibly stalls at the
 * bottom is worse than a pager, because a pager at least tells you it is waiting.
 */
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

/** Removable mono filter chip — one look for URL-driven and fixture chips. */
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

/**
 * Right-aligned, tabular-figure cell — every numeric column shares it so the
 * digits line up. `title` carries the unrounded value for the columns that
 * abbreviate, so three-digit tokens stay readable without becoming lossy.
 */
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

/** Mono text cell — the identifier and label columns. */
function Text({ children }: { children: React.ReactNode }) {
  return (
    <TD className="truncate font-mono text-fg text-small" style={{ ...CELL_PAD }}>
      {children}
    </TD>
  );
}

/**
 * A payload preview. Clamped to a single line by the column's own fixed width (COLUMN_DEFS), and
 * given the full (already truncated) text as a tooltip — these are prose, and would otherwise push
 * everything numeric off screen.
 */
function Preview({ value }: { value: string | null | undefined }) {
  const text = (value ?? "").trim();
  if (!text) return <Text>—</Text>;
  return (
    <TD className="truncate text-fg text-small" style={{ ...CELL_PAD }} title={text}>
      {text}
    </TD>
  );
}

/**
 * A number that may be absent for two different reasons.
 *
 * `—` is reserved for "settled, and genuinely nothing". A trace still receiving spans shows `…` with
 * a title saying so, because its total is not zero and not unknown — it has simply not been computed
 * yet, and a dash there reads as a finished answer.
 */
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

function Cell({ col, row }: { col: ColumnKey; row: TraceListItem }) {
  switch (col) {
    case "when":
      return <Text>{formatWhen(row.started_at)}</Text>;
    case "endedAt":
      return <Text>{formatWhen(row.ended_at)}</Text>;
    case "name":
      // The dot is the whole Status column, folded into the one cell people
      // already read. It is hidden from assistive tech only because the adjacent
      // sr-only text says the same thing in words.
      return (
        <TD className="font-mono text-fg text-small" style={{ ...CELL_PAD }}>
          <span className="flex items-center gap-1.75">
            <span
              aria-hidden="true"
              className={cn("shrink-0 rounded-pill", row.status === "error" ? "bg-error" : "bg-transparent")}
              style={{ width: 6, height: 6 }}
            />
            {row.status === "error" && <span className="sr-only">errored: </span>}
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
    case "session":
      return <Text>{row.session ?? "—"}</Text>;
    case "traceId":
      return <Text>{row.id}</Text>;
    case "callSite":
      return <Text>{row.call_site_id ?? "—"}</Text>;
    case "latency":
      // Not a rollup: latency derives from the trace's own start and end, so it is known the moment
      // the turn finishes, whether or not anything has been summed yet.
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
    case "sessionExpand":
      // Never rendered for a flat (unpinned) row — the pinned expand column only exists in grouped mode,
      // where SessionCell/SessionGroupRows render it instead.
      return null;
  }
}

/**
 * The one empty state, whichever way the list came back empty.
 *
 * A filter matching nothing and a project that has never received a trace look
 * identical from here — the API reports how many rows matched, not why there
 * were none — and the reader's next move is the same in both cases: widen the
 * window, or drop the filters. Each way out is offered only when it would
 * actually change the result, so an all-time unfiltered blank does not suggest
 * clearing filters that are not set.
 *
 * The source-connection pointer stays for the unfiltered case: with nothing
 * narrowing the list, "you have not connected anything yet" is the likeliest
 * explanation and the only one the reader cannot act on from this page.
 */
function NothingHere({
  filtered,
  allTime,
  range,
  onClearAll,
  onSearchAllTime,
}: {
  filtered: boolean;
  allTime: boolean;
  range: string;
  onClearAll: () => void;
  onSearchAllTime: () => void;
}) {
  return (
    <div className="text-center pt-18 px-0 pb-10">
      <p className="text-h1 mx-auto text-fg" style={{ maxWidth: 560 }}>
        {filtered ? "No traces match these filters" : allTime ? "No traces yet" : "No traces in this range"}
      </p>

      {(filtered || !allTime) && (
        <p className="text-muted mt-3 text-body">
          {!allTime && (
            <button
              type="button"
              onClick={onSearchAllTime}
              className="text-accent hover:underline cursor-pointer">
              Search all time
            </button>
          )}
          {!allTime && filtered && <span className="text-subtle"> · </span>}
          {filtered && (
            <button type="button" onClick={onClearAll} className="text-accent hover:underline cursor-pointer">
              Clear all filters
            </button>
          )}
        </p>
      )}

      {!filtered && (
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
