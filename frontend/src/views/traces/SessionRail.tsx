// SPDX-License-Identifier: Apache-2.0
/*
 * Rail preview of a session, opened from the Traces index (grouped mode) row click.
 * URL-addressable via ?session=<id> — same convention as TraceRail's ?trace=<id>, and deliberately the
 * exact same shell: Rail, ViewSegment, Conversation/Tree/Timeline chrome. The only content difference
 * is that each view is stitched across every trace in the session — see detail-views.tsx's
 * SessionConversationView/SessionTreeView/SessionTimelineView, which reuse the trace views unchanged.
 *
 * ⤢ opens the full session page in a NEW TAB, so the list keeps its filters and scroll position.
 */
import { useState } from "react";
import { ErrorNote, LoadingRow, Rail } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { SessionConversationView, SessionTreeView, SessionTimelineView } from "./detail-views";
import { useSessionDetail, useSessionSpans, useSpansByTrace, sessionSummary } from "./session-detail-data";

export function SessionRail({ sessionId, onClose }: { sessionId: string | null; onClose: () => void }) {
  const { orgSlug, projectSlug } = useTenant();
  const q = useSessionDetail(sessionId);
  const spansQ = useSessionSpans(sessionId);

  const [view, setView] = useState<TraceView>("conversation");
  const [focusId, setFocusId] = useState<string | null>(null);
  const spansByTrace = useSpansByTrace(spansQ.data?.spans);

  if (!sessionId) return null;

  const detail = q.data;
  const traces = detail?.traces ?? [];
  const loading = q.isLoading || spansQ.isLoading;
  const error = q.error ?? spansQ.error;

  return (
    <Rail
      open
      onClose={onClose}
      title={sessionId}
      monoTitle
      meta={detail ? sessionSummary(detail, spansQ.data?.spans_truncated ?? false) : undefined}
      onExpand={() => window.open(`/orgs/${orgSlug}/projects/${projectSlug}/sessions/${sessionId}`, "_blank")}
      expandLabel="Open session"
      aria-label="Session detail"
    >
      <div className="flex items-center gap-2 mb-4">
        <ViewSegment view={view} onChange={setView} compact />
      </div>

      {loading && <LoadingRow />}
      {error && <ErrorNote error={error} />}

      {detail && (
        <TraceMedia>
          {view === "conversation" && (
            <SessionConversationView traces={traces} spansByTrace={spansByTrace} focusId={focusId} />
          )}
          {view === "tree" && (
            <SessionTreeView
              traces={traces}
              spansByTrace={spansByTrace}
              focusId={focusId}
              onSelect={setFocusId}
            />
          )}
          {view === "timeline" && (
            <SessionTimelineView
              traces={traces}
              spansByTrace={spansByTrace}
              focusId={focusId}
              onSelect={setFocusId}
            />
          )}
        </TraceMedia>
      )}
    </Rail>
  );
}
