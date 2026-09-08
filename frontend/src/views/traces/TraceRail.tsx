// SPDX-License-Identifier: Apache-2.0
/*
 * Rail preview of a trace, opened from the Traces index row click.
 * URL-addressable via ?trace=<id> — TracesIndex owns the URL and stays mounted,
 * so the list renders behind the rail (unlike navigating to /traces/:id, which
 * would replace it). Opening the panel beside the row IS the industry
 * convention this surface conforms to.
 *
 * ⤢ opens the full trace page in a NEW TAB, so the list keeps its filters and
 * scroll position.
 */
import { useState } from "react";
import { ErrorNote, LoadingRow, Rail } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { ConversationView, TimelineView, TreeView } from "./detail-views";
import { clockLabel, traceSummary, useTraceDetail } from "./detail-data";

export function TraceRail({ traceId, onClose }: { traceId: string | null; onClose: () => void }) {
  const { orgSlug, projectSlug } = useTenant();
  const q = useTraceDetail(traceId);

  const [view, setView] = useState<TraceView>("conversation");
  const [focusId, setFocusId] = useState<string | null>(null);

  if (!traceId) return null;

  const detail = q.data;
  const trace = detail?.trace;
  const spans = detail?.spans ?? [];

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
          {view === "conversation" && <ConversationView spans={spans} focusId={focusId} />}
          {view === "tree" && <TreeView spans={spans} focusId={focusId} onSelect={setFocusId} />}
          {view === "timeline" && (
            <TimelineView trace={trace} spans={spans} focusId={focusId} onSelect={setFocusId} />
          )}
        </TraceMedia>
      )}
    </Rail>
  );
}
