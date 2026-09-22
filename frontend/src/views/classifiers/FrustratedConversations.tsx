// SPDX-License-Identifier: Apache-2.0
/*
 * The conversations a frustration finding cites, and one of them at a time as it happened.
 *
 * <h2>The list, then the conversation</h2>
 * The left column is the evidence: each flagged message with its score, newest first. Selecting one draws
 * the turn that fired with the two before it, from the real traces, through the session view's own
 * conversation components, so tool calls, markdown and folded history read exactly as they do there. The
 * flagged user message is drawn in the error tint: that is the one the finding is about.
 *
 * <h2>Why not the scored request</h2>
 * What the classifier was sent is a clipped, text-only copy with no tool calls, and retention clears it.
 * The traces are what the user saw. The list's preview line is the only place the scored copy shows,
 * because it is one line and has no other source.
 *
 * <h2>Only the selected conversation is fetched</h2>
 * A finding cites up to fifty conversations; each is three trace reads. They load when selected, keyed
 * like the trace page's own read so a trace opened from here is already cached there.
 */
import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueries } from "@tanstack/react-query";
import type { FrustratedConversation } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { Button, cn } from "../../ui";
import { SessionConversationView } from "../traces/detail-views";
import type { Span } from "../traces/detail-data";

const DAY_TIME: Intl.DateTimeFormatOptions = {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
};

export function FrustratedConversations({
  conversations,
  total,
  basePath,
}: {
  conversations: FrustratedConversation[];
  /** Frustrated conversations the rate counted, of which `conversations` are the ones kept as evidence. */
  total: number;
  basePath: string;
}) {
  const [picked, setPicked] = useState<string | null>(null);
  const selected = conversations.find((c) => c.traceId === picked) ?? conversations[0];

  if (conversations.length === 0) {
    return (
      <p className="text-subtle m-0 text-body" style={{ maxWidth: 560 }}>
        No frustrated conversations are stored for this finding. Their traces may have aged out.
      </p>
    );
  }

  return (
    <div
      className="grid rounded-card border border-border overflow-hidden bg-surface"
      style={{ gridTemplateColumns: "320px minmax(0, 1fr)", height: 720 }}
    >
      <div className="flex flex-col min-h-0 border-r border-border">
        <ul className="m-0 p-0 list-none overflow-y-auto flex-1" aria-label="Frustrated conversations">
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
        </ul>
        <p className="m-0 border-t border-border py-2.5 px-4 text-small text-muted">
          {conversations.length === total
            ? `${total.toLocaleString()} ${total === 1 ? "conversation" : "conversations"}`
            : `${conversations.length.toLocaleString()} of ${total.toLocaleString()} kept as evidence`}
        </p>
      </div>
      {selected && <Conversation key={selected.traceId} row={selected} basePath={basePath} />}
    </div>
  );
}

/** One flagged conversation: the turns before the flagged one and the flagged one, as the traces hold them. */
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
              View conversation
            </Link>
          )}
        </span>
      </div>
      <div className="flex-1 overflow-y-auto py-4.5 px-5">
        <p className="mt-0 mb-3.5 text-small text-muted">
          {row.cleared ? "Cleared" : "Flagged"} with a score of {row.score != null ? row.score.toFixed(2) : "–"}
          {row.flaggedAt ? ` on ${new Date(row.flaggedAt).toLocaleString(undefined, DAY_TIME)}` : ""}.
          {before > 0 ? ` Showing the ${before === 1 ? "turn" : `${before} turns`} before it.` : ""}
        </p>
        {loading ? (
          <p className="m-0 text-small text-muted">Loading conversation...</p>
        ) : flaggedMissing ? (
          <p className="m-0 text-body text-subtle" style={{ maxWidth: 520 }}>
            This conversation's traces are no longer stored, so its messages can't be shown.
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
  );
}

/** A filter over a conversation list, one chip per group plus "All". */
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
    <div role="group" aria-label="Filter conversations" className="flex flex-wrap gap-1.5 mb-3">
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
