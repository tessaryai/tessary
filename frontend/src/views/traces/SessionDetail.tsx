// SPDX-License-Identifier: Apache-2.0
/*
 * Session interior — the full page, opened from the rail's ⤢. Same shell as TraceDetail: Conversation |
 * Tree | Timeline over the session's spans, stitched across every trace in it. No Verdicts panel here —
 * judgment is per-trace, and a session-wide verdicts read is out of scope (see detail-views.tsx's
 * SessionConversationView doc comment).
 *
 * URL contract: ?view=tree|timeline (conversation default).
 */
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ErrorNote, LoadingRow, PageHeader } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { SessionConversationView, SessionTreeView, SessionTimelineView } from "./detail-views";
import { useSessionDetail, useSessionSpans, useSpansByTrace, sessionSummary } from "./session-detail-data";

export function SessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const [sp, setSp] = useSearchParams();

  const q = useSessionDetail(sessionId);
  const spansQ = useSessionSpans(sessionId);
  const detail = q.data;
  const traces = detail?.traces ?? [];
  const spansByTrace = useSpansByTrace(spansQ.data?.spans);

  const patch = (kv: Record<string, string | null>) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(kv)) {
      if (v === null) next.delete(k);
      else next.set(k, v);
    }
    setSp(next, { replace: true });
  };

  const view = (sp.get("view") as TraceView | null) ?? "conversation";
  const focusId = sp.get("span");
  const select = (id: string) => patch({ span: id });

  return (
    <div className="pt-7 px-10 pb-14">
      <nav aria-label="Breadcrumb" className="flex items-center gap-1.75 mb-4 text-small">
        <Link to={`${basePath}/traces`} className="text-muted hover:text-fg transition-colors">
          Traces
        </Link>
        <span aria-hidden="true" className="text-subtle">
          ›
        </span>
        <span className="font-mono text-fg">{detail?.id ?? sessionId}</span>
      </nav>

      {!detail &&
        (q.isFetching ? (
          <LoadingRow />
        ) : (
          <ErrorNote
            error={q.error ?? "This session could not be loaded. It may have been deleted."}
          />
        ))}

      {detail && (
        <>
          <PageHeader
            title={<span className="font-mono">{detail.id}</span>}
            subtitle={sessionSummary(detail, spansQ.data?.spans_truncated ?? false)}
          />

          <div className="flex items-center gap-2.5 mt-1.5 mx-0 mb-4.5">
            <ViewSegment view={view} onChange={(v) => patch({ view: v === "conversation" ? null : v })} />
          </div>

          <TraceMedia>
            {view === "conversation" && (
              <SessionConversationView traces={traces} spansByTrace={spansByTrace} focusId={focusId} />
            )}
            {view === "tree" && (
              <SessionTreeView traces={traces} spansByTrace={spansByTrace} focusId={focusId} onSelect={select} />
            )}
            {view === "timeline" && (
              <SessionTimelineView
                traces={traces}
                spansByTrace={spansByTrace}
                focusId={focusId}
                onSelect={select}
              />
            )}
          </TraceMedia>
        </>
      )}
    </div>
  );
}
