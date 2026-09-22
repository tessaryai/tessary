// SPDX-License-Identifier: Apache-2.0
/*
 * The sessions a frustration finding cites, and one of them at a time as it happened.
 *
 * <h2>The list, then the session</h2>
 * The left column is the evidence: each flagged message with its score, newest flag first. Selecting one draws
 * the turn that fired with the two before it, from the real traces, through the session view's own
 * conversation components, so tool calls, markdown and folded history read exactly as they do there. The
 * flagged user message is drawn in the error tint: that is the one the finding is about.
 *
 * <h2>Why not the scored request</h2>
 * What the classifier was sent is a clipped, text-only copy with no tool calls, and retention clears it.
 * The traces are what the user saw. The list's preview line is the only place the scored copy shows,
 * because it is one line and has no other source.
 *
 * <h2>A page at a time</h2>
 * A finding cites every frustrated session, which can run to thousands. The first page comes with the finding;
 * scrolling to the end of the list reads the next from the finding's frustrated-sessions page. A filter (one RCA
 * cause's sessions) reads its own pages from the start. Only the selected session's traces are fetched, keyed
 * like the trace page's own read so a trace opened from here is already cached there.
 */
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useInfiniteQuery, useQueries } from "@tanstack/react-query";
import type { FrustratedConversation, FrustratedSessionPage } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, cn } from "../../ui";
import { SessionConversationView } from "../traces/detail-views";
import type { Span } from "../traces/detail-data";

/** Space left under the flagged message when the pane scrolls to it, in px. */
const FLAGGED_BOTTOM_GAP = 24;

/** Sessions read per page past the first. */
const PAGE_SIZE = 50;

const DAY_TIME: Intl.DateTimeFormatOptions = {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
};

/** One RCA cause's share of the sessions: the report that found it and its 0-based position there. */
export type SessionFilter = { rcaReport: string; index: number };

export function FrustratedConversations({
  findingId,
  first,
  basePath,
  filter,
}: {
  findingId: string;
  /** The page the finding came with: its first sessions and where the next page starts. */
  first: { rows: FrustratedConversation[]; nextCursor: string | null; total: number };
  basePath: string;
  /** Narrows the list to one cause; read from the server, since its sessions may be past the first page. */
  filter?: SessionFilter;
}) {
  const { api } = useTenant();
  const filterKey = filter ? `${filter.rcaReport}:${filter.index}` : "all";
  const pages = useInfiniteQuery({
    queryKey: ["frustrated-sessions", api.base, findingId, filterKey],
    queryFn: ({ pageParam }: { pageParam: string | null }) =>
      api.getFrustratedSessions(findingId, {
        limit: PAGE_SIZE,
        cursor: pageParam,
        cause: filter,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last: FrustratedSessionPage) => last.nextCursor ?? undefined,
    initialData: filter ? undefined : { pages: [first], pageParams: [null] },
    staleTime: Infinity,
  });
  const conversations = pages.data?.pages.flatMap((p) => p.rows) ?? [];
  const total = pages.data?.pages[0]?.total ?? first.total;

  const [picked, setPicked] = useState<string | null>(null);
  const selected = conversations.find((c) => c.traceId === picked) ?? conversations[0];

  // Read the next page when the end of the list scrolls into view.
  const list = useRef<HTMLUListElement>(null);
  const end = useRef<HTMLLIElement>(null);
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = pages;
  useEffect(() => {
    const root = list.current;
    const target = end.current;
    if (!root || !target || !hasNextPage || typeof IntersectionObserver === "undefined") return;
    const seen = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting) && !isFetchingNextPage) void fetchNextPage();
      },
      { root, rootMargin: "200px" },
    );
    seen.observe(target);
    return () => seen.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  if (pages.isLoading) {
    return <p className="text-small text-muted m-0">Loading sessions...</p>;
  }
  if (conversations.length === 0) {
    return (
      <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
        No frustrated sessions are stored for this finding. Their traces may have aged out.
      </p>
    );
  }

  return (
    <div
      className="grid rounded-card border border-border overflow-hidden bg-surface"
      style={{ gridTemplateColumns: "320px minmax(0, 1fr)", height: 720 }}
    >
      <div className="flex flex-col min-h-0 border-r border-border">
        <ul ref={list} className="m-0 p-0 list-none overflow-y-auto flex-1" aria-label="Frustrated sessions">
          {conversations.map((c) => {
            const on = c.traceId === selected?.traceId;
            return (
              <li key={c.traceId}>
                <button
                  type="button"
                  aria-pressed={on}
                  onClick={() => setPicked(c.traceId)}
                  className={cn(
                    "flex w-full flex-col text-left cursor-pointer border-b border-border py-3 px-4 transition-colors",
                    on ? "bg-raised" : "bg-surface hover:bg-hover",
                  )}
                  style={{ transitionDuration: "var(--duration-micro)" }}
                >
                  <span className="flex justify-between font-mono text-small text-muted">
                    <span>{c.flaggedAt ? new Date(c.flaggedAt).toLocaleString(undefined, DAY_TIME) : "–"}</span>
                    <span className="tabular-nums">{c.score != null ? c.score.toFixed(2) : "–"}</span>
                  </span>
                  <span
                    className={cn("mt-1 text-small", on ? "text-fg font-medium" : "text-fg-secondary")}
                    style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
                  >
                    {c.message ?? <span className="font-mono text-muted">{c.conversationId}</span>}
                  </span>
                  {c.cleared && <span className="mt-1 text-small text-muted">Cleared</span>}
                </button>
              </li>
            );
          })}
          {hasNextPage && (
            <li ref={end} className="py-2.5 px-4">
              <Button size="sm" variant="ghost" loading={isFetchingNextPage} onClick={() => void fetchNextPage()}>
                Load more sessions
              </Button>
            </li>
          )}
        </ul>
        <p className="m-0 border-t border-border py-2.5 px-4 text-small text-muted">
          {conversations.length >= total
            ? `${total.toLocaleString()} ${total === 1 ? "session" : "sessions"}`
            : `${conversations.length.toLocaleString()} of ${total.toLocaleString()} sessions`}
        </p>
      </div>
      {selected && <Conversation key={selected.traceId} row={selected} basePath={basePath} />}
    </div>
  );
}

/** One flagged session: the turns before the flagged one and the flagged one, as the traces hold them. */
function Conversation({ row, basePath }: { row: FrustratedConversation; basePath: string }) {
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

  // The flagged message is usually the last user turn, below the turns that led to it. Once the traces
  // are drawn, scroll this pane (never the page) so it is the last thing in view, with the turns before
  // it filling the pane above. Long messages fold themselves after they render, and on a first page load
  // fonts and the rest of the page are still settling, so the pane re-aligns whenever its content
  // resizes, until the reader touches it: a scroll, a key, a tap or a click hands the pane to them.
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
        <span className="font-mono text-muted truncate">{row.conversationId}</span>
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
        </span>
      </div>
      {/* No scroll anchoring: the browser's own adjustment moves the pane as messages fold, and would
          undo the alignment above. */}
      <div ref={pane} className="flex-1 overflow-y-auto py-4.5 px-5" style={{ overflowAnchor: "none" }}>
        <div ref={content}>
        <p className="mt-0 mb-3.5 text-small text-muted">
          {row.cleared ? "Cleared" : "Flagged"} with a score of {row.score != null ? row.score.toFixed(2) : "–"}
          {row.flaggedAt ? ` on ${new Date(row.flaggedAt).toLocaleString(undefined, DAY_TIME)}` : ""}.
          {before > 0 ? ` Showing the ${before === 1 ? "turn" : `${before} turns`} before it.` : ""}
        </p>
        {loading ? (
          <p className="m-0 text-small text-muted">Loading session...</p>
        ) : flaggedMissing ? (
          <p className="m-0 text-body text-subtle" style={{ maxWidth: 520 }}>
            This session's traces are no longer stored, so its messages can't be shown.
          </p>
        ) : (
          <SessionConversationView
            traces={loaded.map((d) => d.trace)}
            spansByTrace={spansByTrace}
            focusId={null}
            flaggedTraceId={row.traceId}
          />
        )}
        </div>
      </div>
    </div>
  );
}

/** A filter over a session list, one chip per group plus "All". */
export function ConversationFilter({
  options,
  value,
  onChange,
}: {
  options: { key: string; label: string }[];
  value: string;
  onChange: (key: string) => void;
}) {
  return (
    <div role="group" aria-label="Filter sessions" className="flex flex-wrap gap-1.5 mb-3">
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
