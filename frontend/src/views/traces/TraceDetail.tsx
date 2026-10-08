// SPDX-License-Identifier: Apache-2.0
/*
 * Trace interior — the full page. Conversation | Tree | Timeline over the trace's
 * real span tree, plus JSON, the response as the API sent it. Judgment is not a
 * view: it is a separate question from execution and stays put while the
 * execution views switch underneath.
 *
 * URL contract (every state a URL):
 *   ?view=tree|timeline|json (conversation default) · ?span=<spanId>.
 */
import { useMemo } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ErrorNote, LoadingRow, PageHeader } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { ConversationView, TimelineView, TreeView } from "./detail-views";
import { namesBy } from "./detection-marker";
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

  /** Patch search params in place (null deletes); view state never stacks history. */
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

  const spans = useMemo(() => detail?.spans ?? [], [detail]);
  const detections = useMemo(() => detail?.detections ?? [], [detail]);
  const marksBySpan = useMemo(() => namesBy(detections, "span_id"), [detections]);
  const turnMarks = useMemo(() => [...new Set(detections.map((d) => d.name))], [detections]);

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

      {/*
       * Exhaustive by construction: with no trace to show we are either still fetching, or we are not
       * and owe the reader a reason. The previous pair — isLoading, then isError — looked exhaustive
       * and was not. A query that has FAILED but is between retries, or is paused because the browser
       * is offline, sits at status 'pending' with fetchStatus 'paused': isLoading is false because
       * nothing is in flight and isError is false because the retries are not spent, so the page
       * rendered its breadcrumb and NOTHING else. That blank is how a dead trace id presents, and dead
       * trace ids are now expected — 0083 drops the v1->v2 id map without re-keying the ids embedded in
       * finding evidence, so every witness chip on a pre-cutover finding lands here. A page that goes
       * blank reads as a broken app; "trace not found" reads as the answer it actually is.
       */}
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
            <ViewSegment view={view} onChange={(v) => patch({ view: v === "conversation" ? null : v })} json />
          </div>

          <div className="flex items-start gap-6">
            <div className="min-w-0 flex-1">
              <TraceMedia>
                {view === "conversation" && <ConversationView spans={spans} focusId={focusId} marks={turnMarks} />}
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
