// SPDX-License-Identifier: Apache-2.0
import { lowerFirst, measureOf, viewOf } from "../metricsRules";
import type { MetricsCatalog, MetricsCatalogView, MetricsChart, MetricsFilter, MetricWidgetDefinition } from "../wire";

export type WidgetDraft = {
  title: string;
  chart: MetricsChart;
  view: string;
  measures: string[];
  note: string | null;
  dimension: string | null;
  topN: number | null;
  compare: boolean;
  /** The editor has no filter control; a widget's stored filters ride through an edit unchanged. */
  filters: MetricsFilter[] | null;
};

export const MAX_TITLE = 80;
const MAX_ID = 40;

export function emptyDraft(catalog: MetricsCatalog): WidgetDraft {
  const view = catalog.views[0];
  return {
    title: "", chart: "number", view: view.name, measures: [view.measures[0].name], note: null, dimension: null,
    topN: null, compare: false, filters: [],
  };
}

export function draftOf(w: MetricWidgetDefinition): WidgetDraft {
  const number = w.chart === "number";
  return {
    title: w.title ?? "",
    chart: w.chart,
    view: w.view,
    measures: number ? [w.measures[0]] : w.measures,
    note: number ? (w.measures[1] ?? null) : null,
    dimension: w.dimension,
    topN: w.top_n,
    compare: w.compare != null,
    filters: w.filters,
  };
}

const capTopN = (topN: number | null, max: number) => (topN != null && topN > max ? null : topN);

export function switchChart(d: WidgetDraft, chart: MetricsChart): WidgetDraft {
  if (chart === "number") return { ...d, chart, measures: d.measures.slice(0, 1), dimension: null, topN: null };
  if (chart === "timeseries") {
    return { ...d, chart, measures: d.measures.slice(0, 1), note: null, compare: false, topN: capTopN(d.topN, 5) };
  }
  return { ...d, chart, note: null, compare: false, topN: capTopN(d.topN, 100) };
}

export function switchView(d: WidgetDraft, view: string, catalog: MetricsCatalog): WidgetDraft {
  const v = viewOf(catalog, view);
  return {
    ...d,
    view,
    measures: v ? [v.measures[0].name] : [],
    note: null,
    dimension: v && d.chart === "table" ? v.dimensions[0].name : null,
  };
}

export function topNOptions(chart: MetricsChart): number[] {
  if (chart === "timeseries") return [1, 2, 3, 4, 5];
  if (chart === "table") return [5, 10, 20, 50, 100];
  return [];
}

const outside = (topN: number | null, max: number) => topN != null && (topN < 1 || topN > max);

export function validateDraft(d: WidgetDraft, catalog: MetricsCatalog): string | null {
  const view = viewOf(catalog, d.view);
  if (!view) return "Pick a view.";
  const asked = d.note == null ? d.measures : [...d.measures, d.note];
  if (d.measures.length === 0 || asked.some((m) => !measureOf(view, m))) return "Pick a measure.";
  if (new Set(d.measures).size !== d.measures.length) return "Pick each measure once.";
  if (d.note === d.measures[0]) return "Pick a note other than the measure.";
  if (d.chart === "number" && d.measures.length !== 1) return "A number takes one measure.";
  if (d.chart === "timeseries" && d.measures.length !== 1) return "A time series takes one measure.";
  if (d.chart === "table" && d.measures.length > 4) return "A table takes 1 to 4 measures.";
  if (d.chart === "table" && d.dimension == null) return "A table needs a dimension.";
  if (d.chart === "timeseries" && outside(d.topN, 5)) return "Top N is 1 to 5 on a time series.";
  if (d.chart === "table" && outside(d.topN, 100)) return "Top N is 1 to 100 on a table.";
  if (d.title.trim().length > MAX_TITLE) return "The title is longer than 80 characters.";
  return null;
}

function titleFrom(view: MetricsCatalogView | undefined, measure: string, dimension: string | null): string | null {
  const label = measureOf(view, measure)?.label;
  if (label == null) return null;
  if (dimension == null) return label;
  const dim = view?.dimensions.find((x) => x.name === dimension)?.label ?? dimension;
  return `${label} by ${lowerFirst(dim)}`;
}

export function autoTitle(d: WidgetDraft, catalog: MetricsCatalog): string {
  return titleFrom(viewOf(catalog, d.view), d.measures[0], d.dimension) ?? d.measures[0] ?? "";
}

export function autoTitleOf(w: MetricWidgetDefinition, view: MetricsCatalogView | undefined): string {
  return titleFrom(view, w.measures[0], w.dimension) ?? w.id;
}

const cut = (s: string, max: number) => s.slice(0, max).replace(/-$/, "");

export function widgetId(title: string, taken: string[]): string {
  const base = cut(title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-/, ""), MAX_ID) || "widget";
  let id = base;
  for (let n = 2; taken.includes(id); n++) {
    const suffix = `-${n}`;
    id = `${cut(base, MAX_ID - suffix.length)}${suffix}`;
  }
  return id;
}

export function widgetOf(
  d: WidgetDraft,
  id: string,
  placement: Pick<MetricWidgetDefinition, "x" | "y" | "w" | "h">,
): MetricWidgetDefinition {
  return {
    id,
    title: d.title.trim() || null,
    chart: d.chart,
    view: d.view,
    measures: d.chart === "number" && d.note != null ? [d.measures[0], d.note] : d.measures,
    dimension: d.dimension,
    filters: d.filters,
    top_n: d.topN,
    compare: d.compare ? "previous_window" : null,
    ...placement,
  };
}
