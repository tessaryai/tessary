// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import {
  companionNotes, deltaText, formatValue, measureOf, noteText, RANGE_PRESETS, rangeNoun, type Window,
} from "../metricsRules";
import type {
  MetricsCatalogMeasure, MetricsCatalogView, MetricsQueryResult, MetricsRow, MetricWidgetDefinition,
} from "../wire";

export type WidgetBodyProps = {
  widget: MetricWidgetDefinition;
  /** Undefined when the catalog does not know the view (a stale stored widget). */
  view: MetricsCatalogView | undefined;
  result: MetricsQueryResult;
  window: Window;
  basePath: string;
  /** In edit mode nothing in the body links away, so a click cannot leave the page and drop the draft. */
  editing: boolean;
};

/** The tile's card holds the figure, the delta and three lines at an h3 placement. */
const MAX_NOTES = 3;

/** A measure the catalog no longer lists prints its wire name. */
const measureOrName = (view: MetricsCatalogView | undefined, name: string): MetricsCatalogMeasure =>
  measureOf(view, name) ?? { name, label: name, unit: "count", gap_fill: "none", companions: [] };

/** The body gets the window, not the range, so a window as long as a preset is named after it. */
function previousNoun(window: Window): string {
  const preset = RANGE_PRESETS.find((p) => p.ms === Date.parse(window.to) - Date.parse(window.from));
  return rangeNoun(preset ? { kind: "preset", key: preset.key } : { kind: "custom", ...window });
}

/** No row reads as an empty window: a count is 0 and a ratio has no value. */
const valueIn = (row: MetricsRow | undefined, measure: MetricsCatalogMeasure) =>
  row ? row.values[measure.name] : measure.gap_fill === "zero" ? 0 : null;

export function NumberWidget({ widget, view, result, window, basePath, editing }: WidgetBodyProps) {
  const [m0, m1] = widget.measures;
  const row = result.rows.at(0);
  const measure = measureOrName(view, m0);
  const note = m1 ? measureOrName(view, m1) : undefined;
  const { unit } = measure;
  const current = valueIn(row, measure);
  const previous = result.previous_rows ? valueIn(result.previous_rows.at(0), measure) : null;
  const value = formatValue(unit, current);
  const notes = [
    ...(note ? [noteText(note, valueIn(row, note))] : []),
    ...companionNotes(widget.view, row?.values ?? {}, row?.n ?? 0, widget.measures),
  ].slice(0, MAX_NOTES);

  return (
    <div className="flex flex-col overflow-hidden">
      <div className="font-mono text-metric text-fg tabular-nums">
        {widget.view === "cases" && !editing ? (
          <Link to={`${basePath}/triage`} className="text-link hover:text-link-hover">
            {value}
          </Link>
        ) : (
          value
        )}
      </div>
      {widget.compare != null && (
        <p aria-label="Change from the previous window" className="text-small text-muted tabular-nums mt-1">
          {`${deltaText(current, previous, unit)} vs previous ${previousNoun(window)}`}
        </p>
      )}
      {notes.length > 0 && (
        <div className="mt-2 flex flex-col gap-0.5">
          {notes.map((note) => (
            <p key={note} className="text-small text-muted">
              {note}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
