// SPDX-License-Identifier: Apache-2.0
// ?session=<id>, the same shell as TraceRail. ⤢ opens a new tab so the list keeps filters and scroll.
import { useState } from "react";
import { ErrorNote, LoadingRow, Rail } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { SessionConversationView, SessionTreeView, SessionTimelineView } from "./detail-views";
import { RawJsonView } from "./detail-json";
import { conversationMarks, namesBy } from "./detection-marker";
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
  const detections = detail?.detections ?? [];
  const said = conversationMarks(detections);
  const marksBySpan = namesBy(detections, "span_id");
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
            <SessionConversationView
                traces={traces}
                spansByTrace={spansByTrace}
                focusId={focusId}
                marksByTrace={said.byTrace}
                spanMarks={said.bySpan}
              />
          )}
          {view === "tree" && (
            <SessionTreeView
              traces={traces}
              spansByTrace={spansByTrace}
              focusId={focusId}
              onSelect={setFocusId}
              marksBySpan={marksBySpan}
            />
          )}
          {view === "timeline" && (
            <SessionTimelineView
              traces={traces}
              spansByTrace={spansByTrace}
              focusId={focusId}
              onSelect={setFocusId}
              marksBySpan={marksBySpan}
            />
          )}
          {view === "json" && spansQ.data && (
            <RawJsonView
              value={{ session: detail, spans: spansQ.data }}
              fileName={`session-${detail.id}.json`}
              foldDepth={3}
            />
          )}
        </TraceMedia>
      )}
    </Rail>
  );
}
