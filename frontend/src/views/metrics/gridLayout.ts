// SPDX-License-Identifier: Apache-2.0
// Widget placements are the server's integers; react-grid-layout's items are a view of them, never their store.
import { verticalCompactor, type Layout, type LayoutItem } from "react-grid-layout";
import { GRID_MARGIN_Y } from "./metricsRules";
import type { MetricsChart, MetricWidgetDefinition } from "./wire";

export { ROW_HEIGHT } from "./metricsRules";

/** MetricDashboardValidator's bounds. */
export const COLUMNS = 12;
export const MIN_W = 3;
export const MAX_W = 12;
export const MIN_H = 2;
export const MAX_H = 8;
export const GRID_MARGIN: readonly [number, number] = [16, GRID_MARGIN_Y];

export type Placement = Pick<MetricWidgetDefinition, "x" | "y" | "w" | "h">;

const NEW_SIZE: Record<MetricsChart, { w: number; h: number }> = {
  number: { w: 4, h: 3 },
  timeseries: { w: 6, h: 5 },
  table: { w: 12, h: 5 },
};

export function toLayout(widgets: MetricWidgetDefinition[]): LayoutItem[] {
  return widgets.map((w) => ({
    i: w.id, x: w.x, y: w.y, w: w.w, h: w.h, minW: MIN_W, maxW: MAX_W, minH: MIN_H, maxH: MAX_H,
  }));
}

export function fromLayout(widgets: MetricWidgetDefinition[], layout: Layout): MetricWidgetDefinition[] {
  return widgets.map((w) => {
    const item = layout.find((l) => l.i === w.id);
    return item ? { ...w, x: item.x, y: item.y, w: item.w, h: item.h } : w;
  });
}

export function placementsEqual(a: MetricWidgetDefinition[], b: MetricWidgetDefinition[]): boolean {
  return (
    a.length === b.length &&
    a.every((w, i) => w.id === b[i].id && w.x === b[i].x && w.y === b[i].y && w.w === b[i].w && w.h === b[i].h)
  );
}

/** The compactor moves the items it is handed, so it always gets a fresh layout. */
export function compactWidgets(widgets: MetricWidgetDefinition[]): MetricWidgetDefinition[] {
  return fromLayout(widgets, verticalCompactor.compact(toLayout(widgets), COLUMNS));
}

export function readingOrder(widgets: MetricWidgetDefinition[]): MetricWidgetDefinition[] {
  return [...widgets].sort((a, b) => a.y - b.y || a.x - b.x || a.id.localeCompare(b.id));
}

/** Each widget keeps its size and takes the neighbour's position; compaction then settles any overlap. */
export function moveWidget(
  widgets: MetricWidgetDefinition[],
  id: string,
  dir: "earlier" | "later",
): MetricWidgetDefinition[] {
  const order = readingOrder(widgets);
  const at = order.findIndex((w) => w.id === id);
  const other = order[dir === "earlier" ? at - 1 : at + 1];
  if (!other) return widgets;
  const me = order[at];
  const swapped = widgets.map((w) => {
    const to = w.id === id ? other : w.id === other.id ? me : null;
    return to ? { ...w, x: Math.min(to.x, COLUMNS - w.w), y: to.y } : w;
  });
  return compactWidgets(swapped);
}

export function placementForNew(widgets: MetricWidgetDefinition[], chart: MetricsChart): Placement {
  return { x: 0, y: Math.max(0, ...widgets.map((w) => w.y + w.h)), ...NEW_SIZE[chart] };
}

export function clampPlacement(p: Placement): Placement {
  const w = Math.min(MAX_W, Math.max(MIN_W, p.w));
  return {
    x: Math.min(COLUMNS - w, Math.max(0, p.x)),
    y: Math.max(0, p.y),
    w,
    h: Math.min(MAX_H, Math.max(MIN_H, p.h)),
  };
}
