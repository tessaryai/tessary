// SPDX-License-Identifier: Apache-2.0
// URL contract: ?view=tree|timeline|json (conversation default) · ?span=<spanId>.
import { useMemo } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ErrorNote, LoadingRow, PageHeader } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { searchPatch } from "../../lib/searchPatch";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { ConversationView, TimelineView, TreeView } from "./detail-views";
import { isTurnMark, namesBy } from "./detection-marker";
import { RawJsonView } from "./detail-json";
import { clockLabel, traceSummary, useTraceDetail } from "./detail-data";

export function TraceDetail() {
  const { traceId } = useParams<{ traceId: string }>();
  const { orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const [sp, setSp] = useSearchParams();

  const q = useTraceDetail(traceId);
  const detail = q.data;
  const trace = detail?.trace;

  const patch = searchPatch(sp, setSp, true);

  const view = (sp.get("view") as TraceView | null) ?? "conversation";
  const focusId = sp.get("span");

  const spans = useMemo(() => detail?.spans ?? [], [detail]);
  const detections = useMemo(() => detail?.detections ?? [], [detail]);
  const marksBySpan = useMemo(() => namesBy(detections, "span_id"), [detections]);
  const turnMarks = useMemo(() => [...new Set(detections.filter(isTurnMark).map((d) => d.name))], [detections]);
  const spanMarks = useMemo(() => namesBy(detections.filter((d) => !isTurnMark(d)), "span_id"), [detections]);

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
        <span className="font-mono text-fg">{trace?.id ?? traceId}</span>
      </nav>

      {/* Exhaustive: a failed query between retries, or paused offline, is neither loading nor error, and dead
         trace ids are expected (pre-cutover finding evidence), so say "not found" rather than go blank. */}
      {!trace &&
        (q.isFetching ? (
          <LoadingRow />
        ) : (
          <ErrorNote
            error={
              q.error ??
              "This trace could not be loaded. It may have been deleted or aged out of retention."
            }
          />
        ))}

      {trace && (
        <>
          <PageHeader
            title={<span className="font-mono">{trace.name ?? trace.id}</span>}
            subtitle={`${traceSummary(trace, spans)} · started ${clockLabel(trace.started_at)}`}
          />

          <div className="flex items-center gap-2.5 mt-1.5 mx-0 mb-4.5">
            <ViewSegment view={view} onChange={(v) => patch({ view: v === "conversation" ? null : v })} />
          </div>

          <div className="flex items-start gap-6">
            <div className="min-w-0 flex-1">
              <TraceMedia>
                {view === "conversation" && <ConversationView spans={spans} focusId={focusId} marks={turnMarks} spanMarks={spanMarks} />}
                {view === "tree" && (
                  <TreeView spans={spans} focusId={focusId} onSelect={select} marksBySpan={marksBySpan} />
                )}
                {view === "timeline" && (
                  <TimelineView
                    trace={trace}
                    spans={spans}
                    focusId={focusId}
                    onSelect={select}
                    marksBySpan={marksBySpan}
                  />
                )}
                {view === "json" && detail && (
                  <RawJsonView value={detail} fileName={`trace-${trace.id}.json`} foldDepth={2} />
                )}
              </TraceMedia>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
