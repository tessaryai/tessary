// SPDX-License-Identifier: Apache-2.0
// ?trace=<id>: TracesIndex owns the URL and stays mounted behind the rail. ⤢ opens a new tab.
import { useState } from "react";
import { ErrorNote, LoadingRow, Rail } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { ConversationView, TimelineView, TreeView } from "./detail-views";
import { RawJsonView } from "./detail-json";
import { clockLabel, traceSummary, useTraceDetail } from "./detail-data";
import { isTurnMark, namesBy } from "./detection-marker";

export function TraceRail({ traceId, onClose }: { traceId: string | null; onClose: () => void }) {
  const { orgSlug, projectSlug } = useTenant();
  const q = useTraceDetail(traceId);

  const [view, setView] = useState<TraceView>("conversation");
  const [focusId, setFocusId] = useState<string | null>(null);

  if (!traceId) return null;

  const detail = q.data;
  const trace = detail?.trace;
  const spans = detail?.spans ?? [];
  const detections = detail?.detections ?? [];
  const marksBySpan = namesBy(detections, "span_id");
  const turnMarks = [...new Set(detections.filter(isTurnMark).map((d) => d.name))];
  const spanMarks = namesBy(detections.filter((d) => !isTurnMark(d)), "span_id");

  return (
    <Rail
      open
      onClose={onClose}
      title={trace?.name ?? trace?.id ?? traceId}
      monoTitle
      meta={
        trace
          ? `${traceSummary(trace, spans)} · ${clockLabel(trace.started_at)}`
          : undefined
      }
      onExpand={() => window.open(`/orgs/${orgSlug}/projects/${projectSlug}/traces/${traceId}`, "_blank")}
      expandLabel="Open trace"
      aria-label="Trace detail"
    >
      <div className="flex items-center gap-2 mb-4">
        <ViewSegment view={view} onChange={setView} compact />
      </div>

      {q.isLoading && <LoadingRow />}
      {q.isError && <ErrorNote error={q.error} />}

      {trace && (
        <TraceMedia>
          {view === "conversation" && <ConversationView spans={spans} focusId={focusId} marks={turnMarks} spanMarks={spanMarks} />}
          {view === "tree" && (
            <TreeView spans={spans} focusId={focusId} onSelect={setFocusId} marksBySpan={marksBySpan} />
          )}
          {view === "timeline" && (
            <TimelineView
              trace={trace}
              spans={spans}
              focusId={focusId}
              onSelect={setFocusId}
              marksBySpan={marksBySpan}
            />
          )}
          {view === "json" && detail && (
            <RawJsonView value={detail} fileName={`trace-${trace.id}.json`} foldDepth={2} />
          )}
        </TraceMedia>
      )}
    </Rail>
  );
}
