// SPDX-License-Identifier: Apache-2.0
/*
 * Trace interior — the full page. Conversation | Tree | Timeline over the trace's
 * real span tree, rather than a
 * fourth tab: judgment is a separate question from execution and stays put while
 * the execution views switch underneath.
 *
 * URL contract (every state a URL):
 *   ?view=tree|timeline (conversation default) · ?span=<spanId> ·
 *   ?case=C-118 (evidence mode — the banner back to the case).
 */
import { useMemo } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ErrorNote, LoadingRow, PageHeader } from "../../ui";
import { useTenant } from "../../tenant/TenantContext";
import { TraceMedia, ViewSegment, type TraceView } from "./detail-bits";
import { ConversationView, TimelineView, TreeView } from "./detail-views";
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
  const caseRef = sp.get("case");

  const spans = useMemo(() => detail?.spans ?? [], [detail]);

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

      {/* Evidence mode: arriving from a case, the way back is pinned. */}
      {caseRef && (
        <div
          className="flex items-center bg-surface border border-border gap-3 py-2.5 px-3.5 mb-4.5 text-small"
          style={{ borderRadius: "var(--radius-card)" }}
        >
          <Link to={`${basePath}/cases/${caseRef}`} className="text-link hover:text-link-hover hover:underline">
            Back to {caseRef}
          </Link>
          <span className="text-subtle">You are reading this trace as evidence for a case.</span>
        </div>
      )}

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
            <ViewSegment view={view} onChange={(v) => patch({ view: v === "conversation" ? null : v })} />
          </div>

          <div className="flex items-start gap-6">
            <div className="min-w-0 flex-1">
              <TraceMedia>
                {view === "conversation" && <ConversationView spans={spans} focusId={focusId} />}
                {view === "tree" && <TreeView spans={spans} focusId={focusId} onSelect={select} />}
                {view === "timeline" && (
                  <TimelineView trace={trace} spans={spans} focusId={focusId} onSelect={select} />
                )}
              </TraceMedia>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
