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

const overlaps = (a: Placement, b: Placement) =>
  a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** Vertical compaction leaves a widget where it is when it sits on row 0 or on a widget in one of its columns. */
const resting = (p: Placement, placed: Placement[]) =>
  p.y === 0 || placed.some((o) => o.y + o.h === p.y && o.x < p.x + p.w && p.x < o.x + o.w);

/**
 * The first place after `after` in reading order where `w` fits beside `placed` and compaction leaves it. The search
 * ends by the row under the widget that ends lowest: there `w` rests on that widget and overlaps nothing.
 */
function nextPlace(w: Placement, after: Placement | null, placed: Placement[]): Placement {
  for (let y = after?.y ?? 0; ; y++) {
    for (let x = 0; x + w.w <= COLUMNS; x++) {
      if (after && y === after.y && x <= after.x) continue;
      const p = { x, y, w: w.w, h: w.h };
      if (resting(p, placed) && !placed.some((o) => overlaps(o, p))) return p;
    }
  }
}

/**
 * Swaps the widget with its neighbour in reading order. The widgets before the pair keep their places; the pair and
 * every widget after it are placed again in the new order, each at the first place after the one before it, so the
 * swap always shows and the layout is one compaction leaves alone.
 */
export function moveWidget(
  widgets: MetricWidgetDefinition[],
  id: string,
  dir: "earlier" | "later",
): MetricWidgetDefinition[] {
  const order = readingOrder(widgets);
  const at = order.findIndex((w) => w.id === id);
  const first = dir === "earlier" ? at - 1 : at;
  if (first < 0 || first + 1 >= order.length) return widgets;
  const kept = order.slice(0, first);
  const next = [order[first + 1], order[first], ...order.slice(first + 2)];
  const placed: Placement[] = [...kept];
  const moved = new Map<string, Placement>();
  let after: Placement | null = kept.at(-1) ?? null;
  for (const w of next) {
    after = nextPlace(w, after, placed);
    placed.push(after);
    moved.set(w.id, after);
  }
  return widgets.map((w) => {
    const p = moved.get(w.id);
    return p ? { ...w, ...p } : w;
  });
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
