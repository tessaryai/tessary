// SPDX-License-Identifier: Apache-2.0
// Presentational: the page runs every widget read and hands each card its state, so this file fetches nothing.
import type { ReactNode } from "react";
import { CircleAlert, Clock, GripVertical } from "lucide-react";
import type { ApiError } from "../../api/types";
import { Button, cn, ErrorNote, Skeleton } from "../../ui";
import { autoTitleOf } from "./editor/widgetForm";
import { emptyResult, filteredByCallSite, type Window } from "./metricsRules";
import { NumberWidget } from "./widgets/NumberWidget";
import { TableWidget, tableCaption } from "./widgets/TableWidget";
import { TimeSeriesWidget, timeSeriesCaption } from "./widgets/TimeSeriesWidget";
import type { MetricsCatalogView, MetricsQueryResult, MetricWidgetDefinition } from "./wire";

export type WidgetState =
  | { status: "loading" }
  | { status: "error"; error: unknown; onRetry: () => void }
  | { status: "ready"; result: MetricsQueryResult; stale: boolean };

const BODY = { number: NumberWidget, timeseries: TimeSeriesWidget, table: TableWidget };

/** The server is busy or slow, not refusing: a retry can succeed, so the card says so in grey rather than red. */
function RetryNote({ error, onRetry }: { error: ApiError; onRetry: () => void }) {
  const Icon = error.code === "METRICS.TIMED_OUT" ? Clock : CircleAlert;
  return (
    <div role="alert" className="flex-1 flex flex-col items-center justify-center gap-3 text-center">
      <Icon size={32} strokeWidth={1.5} aria-hidden="true" className="text-muted" />
      <p className="text-body text-fg-secondary max-w-[360px]">{error.detail}</p>
      <code className="font-mono text-small text-muted">{error.code}</code>
      <Button size="sm" variant="secondary" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

function captionOf(widget: MetricWidgetDefinition, result: MetricsQueryResult): string | undefined {
  if (widget.chart === "timeseries") return timeSeriesCaption(result);
  if (widget.chart === "table") return tableCaption(widget);
  return undefined;
}

export function WidgetCard({
  widget,
  view,
  state,
  window,
  basePath,
  callSite,
  editing,
  menu,
  sub,
}: {
  widget: MetricWidgetDefinition;
  view: MetricsCatalogView | undefined;
  state: WidgetState;
  window: Window;
  basePath: string;
  callSite: string | null;
  editing: boolean;
  /** The WidgetMenu, edit mode only. */
  menu?: ReactNode;
  /** Right-aligned caption in the title bar; a chart or call-site table names its own when this is absent. */
  sub?: string;
}) {
  const title = widget.title ?? autoTitleOf(widget, view);
  const caption = sub ?? (state.status === "ready" && view ? captionOf(widget, state.result) : undefined);
  const stale = state.status === "ready" && state.stale;
  const code = state.status === "error" ? (state.error as ApiError | null)?.code : undefined;
  const Body = BODY[widget.chart];

  return (
    <section
      aria-label={title}
      className={cn(
        "h-full min-w-0 flex flex-col gap-2.5 p-4 rounded-card bg-surface border overflow-hidden",
        editing ? "border-dashed border-border-strong" : "border-border",
      )}
    >
      <div className={cn("h-7 shrink-0 flex items-center gap-2", editing && "metrics-drag-handle cursor-grab select-none")}>
        {editing && (
          <span title="Drag to move" aria-hidden="true" className="w-5 h-7 -ml-1.5 shrink-0 inline-flex items-center justify-center text-muted">
            <GripVertical size={16} strokeWidth={1.75} />
          </span>
        )}
        <h3 className={cn("truncate", widget.chart === "number" ? "text-label uppercase text-muted" : "text-h3 text-fg")}>
          {title}
        </h3>
        {caption && <span className="ml-auto text-small text-muted whitespace-nowrap">{caption}</span>}
        {menu && <div className={cn("shrink-0", !caption && "ml-auto")}>{menu}</div>}
      </div>
      {callSite != null && !filteredByCallSite(widget, view) && (
        <p className="text-small text-muted">Not filtered by call site</p>
      )}
      <div
        aria-busy={stale || undefined}
        className={cn("flex-1 min-h-0 min-w-0 flex flex-col", stale && "opacity-40")}
      >
        {state.status === "loading" && (
          <div role="status" aria-label={`Loading ${title}`} className="flex flex-col gap-2.5">
            <Skeleton className="h-8 w-28" />
            <Skeleton className="h-3 w-40" />
          </div>
        )}
        {state.status === "error" &&
          (code === "METRICS.BUSY" || code === "METRICS.TIMED_OUT" ? (
            <RetryNote error={state.error as ApiError} onRetry={state.onRetry} />
          ) : (
            <ErrorNote error={state.error} />
          ))}
        {state.status === "ready" &&
          (view === undefined ? (
            <ErrorNote error={`No metrics view named '${widget.view}'`} />
          ) : emptyResult(state.result) ? (
            <p className="text-small text-muted">Nothing ran in this window.</p>
          ) : (
            <Body widget={widget} view={view} result={state.result} window={window} basePath={basePath} />
          ))}
      </div>
    </section>
  );
}
