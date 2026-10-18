// SPDX-License-Identifier: Apache-2.0
// The preview is the real WidgetCard reading the page's window and call-site filter, so what it shows is what saves.
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CircleAlert, X } from "lucide-react";
import { useProjectApi } from "../../../tenant/TenantContext";
import { Button, Field, IconButton, Input, Rail, SegmentedControl, Select, Toggle } from "../../../ui";
import { placementForNew, type Placement } from "../gridLayout";
import { widgetQuery } from "../metricsQueries";
import {
  GRID_MARGIN_Y, HOUR_MS, rangeLabel, type RangeState, ROW_HEIGHT, viewOf, type Window, widgetRequest,
} from "../metricsRules";
import { WidgetCard, type WidgetState } from "../WidgetCard";
import type { MetricsCatalog, MetricsChart, MetricWidgetDefinition } from "../wire";
import {
  autoTitle, draftOf, emptyDraft, switchChart, switchView, topNOptions, validateDraft, type WidgetDraft, widgetId,
  widgetOf,
} from "./widgetForm";

const CHARTS: { value: MetricsChart; label: string }[] = [
  { value: "number", label: "Number" },
  { value: "timeseries", label: "Time series" },
  { value: "table", label: "Table" },
];

const MAX_MEASURES = 4;

/** The server's auto rule: hourly buckets up to 72 hours, daily beyond. */
const bucketWord = (w: Window) => (Date.parse(w.to) - Date.parse(w.from) <= 72 * HOUR_MS ? "hourly" : "daily");

type Props = {
  open: boolean;
  mode: "add" | "edit";
  /** The widget being edited; null when adding. */
  widget: MetricWidgetDefinition | null;
  catalog: MetricsCatalog;
  /** The draft's widgets: ids to avoid and the placement source for a new widget. */
  widgets: MetricWidgetDefinition[];
  range: RangeState;
  window: Window;
  callSite: string | null;
  basePath: string;
  onSave: (w: MetricWidgetDefinition) => void;
  onClose: () => void;
};

function Label({ children }: { children: string }) {
  return <span className="text-label uppercase text-muted">{children}</span>;
}

/** The Rail mounts this only while open, so every opening starts from the widget or an empty draft, never the last one. */
function EditorRail({ mode, widget, catalog, widgets, range, window, callSite, basePath, onSave, onClose }: Props) {
  const api = useProjectApi();
  const [draft, setDraft] = useState<WidgetDraft>(() => (widget ? draftOf(widget) : emptyDraft(catalog)));
  const view = viewOf(catalog, draft.view);
  const placement: Placement = widget
    ? { x: widget.x, y: widget.y, w: widget.w, h: widget.h }
    : placementForNew(widgets, draft.chart);
  const error = validateDraft(draft, catalog);
  const previewWidget = widgetOf(draft, widget?.id ?? "preview", placement);
  const read = useQuery({
    ...widgetQuery(api, widgetRequest(previewWidget, window, callSite, view)),
    enabled: error == null,
  });
  const state: WidgetState = read.isPending
    ? { status: "loading" }
    : read.isError
      ? { status: "error", error: read.error, onRetry: () => void read.refetch() }
      : { status: "ready", result: read.data, stale: read.isPlaceholderData };

  const set = (patch: Partial<WidgetDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const setMeasure = (i: number, name: string) =>
    setDraft((d) => ({ ...d, measures: d.measures.map((m, j) => (j === i ? name : m)) }));
  const spare = view?.measures.find((m) => !draft.measures.includes(m.name));
  const dimension = view?.dimensions.find((d) => d.name === draft.dimension);
  const number = draft.chart === "number";

  const save = () => {
    const id = widget?.id ?? widgetId(draft.title.trim() || autoTitle(draft, catalog), widgets.map((w) => w.id));
    onSave(widgetOf(draft, id, placement));
  };

  const measureOptions = view?.measures.map((m) => (
    <option key={m.name} value={m.name}>
      {m.label}
    </option>
  ));

  return (
    <div className="flex flex-col gap-4">
      <Field label="Title" hint="Leave blank to name it from the settings.">
        {(p) => (
          <Input {...p} value={draft.title} placeholder={autoTitle(draft, catalog)} onChange={(e) => set({ title: e.target.value })} />
        )}
      </Field>
      <div className="flex flex-col gap-1.5 items-start">
        <Label>Chart</Label>
        <SegmentedControl ariaLabel="Chart" value={draft.chart} options={CHARTS} onChange={(c) => setDraft(switchChart(draft, c))} />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <Field label="View">
          {(p) => (
            <Select {...p} value={draft.view} onChange={(e) => setDraft(switchView(draft, e.target.value, catalog))}>
              {catalog.views.map((v) => (
                <option key={v.name} value={v.name}>
                  {v.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label="Measure">
          {(p) => (
            <Select {...p} value={draft.measures[0]} onChange={(e) => setMeasure(0, e.target.value)}>
              {measureOptions}
            </Select>
          )}
        </Field>
      </div>
      {draft.chart === "table" && (
        <div className="flex flex-col gap-2">
          {draft.measures.slice(1).map((m, j) => (
            <div key={j} className="flex items-center gap-2">
              <Select aria-label={`Measure ${j + 2}`} value={m} onChange={(e) => setMeasure(j + 1, e.target.value)}>
                {measureOptions}
              </Select>
              <IconButton
                size="sm"
                label="Remove measure"
                onClick={() => set({ measures: draft.measures.filter((_, k) => k !== j + 1) })}
              >
                <X size={14} strokeWidth={1.75} aria-hidden="true" />
              </IconButton>
            </div>
          ))}
          {draft.measures.length < MAX_MEASURES && spare && (
            <Button
              size="sm"
              variant="ghost"
              className="self-start"
              onClick={() => set({ measures: [...draft.measures, spare.name] })}
            >
              Add measure
            </Button>
          )}
        </div>
      )}
      {number && (
        <Field label="Note" hint="Shown under the number.">
          {(p) => (
            <Select {...p} value={draft.note ?? ""} onChange={(e) => set({ note: e.target.value || null })}>
              <option value="">None</option>
              {measureOptions}
            </Select>
          )}
        </Field>
      )}
      {!number && (
        <div className="grid grid-cols-2 gap-3">
          <Field
            label="Break down by"
            hint={dimension?.high_cardinality ? "Many distinct values; the chart keeps the top N." : undefined}
          >
            {(p) => (
              <Select {...p} value={draft.dimension ?? ""} onChange={(e) => set({ dimension: e.target.value || null })}>
                <option value="">None</option>
                {view?.dimensions.map((d) => (
                  <option key={d.name} value={d.name}>
                    {d.label}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label="Top N">
            {(p) => (
              <Select
                {...p}
                value={draft.topN ?? ""}
                onChange={(e) => set({ topN: e.target.value === "" ? null : Number(e.target.value) })}
              >
                <option value="">Default</option>
                {topNOptions(draft.chart).map((n) => (
                  <option key={n} value={n}>
                    {n}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      )}
      <div className="flex items-center justify-between gap-4">
        <div className="flex flex-col">
          <span className="text-body text-fg">Compare with previous window</span>
          {!number && <span className="text-small text-muted">Available for Number charts.</span>}
        </div>
        <Toggle
          label="Compare with previous window"
          checked={draft.compare}
          disabled={!number}
          onChange={(compare) => set({ compare })}
        />
      </div>
      <section aria-label="Preview" className="rounded-card border border-border bg-bg p-3 flex flex-col gap-2">
        <div className="flex items-center justify-between gap-3">
          <Label>Preview</Label>
          <span className="text-small text-muted">{`${rangeLabel(range)}, ${bucketWord(window)}`}</span>
        </div>
        {error != null ? (
          <p role="alert" className="text-small text-fg-secondary flex items-center gap-2">
            <CircleAlert size={14} className="text-error shrink-0" aria-hidden="true" />
            {error}
          </p>
        ) : (
          <div style={{ height: placement.h * ROW_HEIGHT + (placement.h - 1) * GRID_MARGIN_Y }}>
            <WidgetCard
              widget={previewWidget}
              view={view}
              state={state}
              window={window}
              basePath={basePath}
              callSite={callSite}
              editing={false}
            />
          </div>
        )}
      </section>
      <div className="flex items-center justify-end gap-2 pt-4 border-t border-border">
        <Button variant="ghost" onClick={onClose}>
          Cancel
        </Button>
        <Button variant="primary" disabled={error != null} onClick={save}>
          {mode === "add" ? "Add widget" : "Save changes"}
        </Button>
      </div>
    </div>
  );
}

export function WidgetEditor(props: Props) {
  const title = props.mode === "add" ? "Add widget" : "Edit widget";
  return (
    <Rail open={props.open} onClose={props.onClose} title={title} aria-label={title}>
      <EditorRail {...props} />
    </Rail>
  );
}
