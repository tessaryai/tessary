// SPDX-License-Identifier: Apache-2.0
/*
 * Widget placements against react-grid-layout's items, with the library's real vertical compactor: what a drag, a
 * resize, a mount-time compaction or a keyboard move writes back onto the dashboard the server validates.
 */
import { describe, expect, it } from "vitest";
import { DEFAULT_DASHBOARD, widget } from "../../test/metricsFixtures";
import {
  clampPlacement, compactWidgets, fromLayout, GRID_MARGIN, moveWidget, placementForNew, placementsEqual, readingOrder,
  type Placement, ROW_HEIGHT, toLayout,
} from "./gridLayout";

const place = (id: string, x: number, y: number, w: number, h: number) => widget({ id, x, y, w, h });
const placements = (ws: { id: string; x: number; y: number; w: number; h: number }[]) =>
  ws.map(({ id, x, y, w, h }) => ({ id, x, y, w, h }));

describe("gridLayout", () => {
  // Bug: the grid's row and gap drift from the 72 px rows and 16 px gaps the chart heights are computed from.
  it("sizesRowsAndGapsAsTheChartsAssume", () => {
    expect(ROW_HEIGHT).toBe(72);
    expect(GRID_MARGIN).toEqual([16, 16]);
  });

  // Bug: a resize below 3 columns or past 8 rows, which the server refuses; or placements written back out of order.
  it("toLayoutCarriesTheValidatorBoundsAndFromLayoutWritesBackInOrder", () => {
    const ws = [place("a", 0, 0, 4, 3), place("b", 4, 0, 8, 5)];
    expect(toLayout(ws)).toEqual([
      { i: "a", x: 0, y: 0, w: 4, h: 3, minW: 3, maxW: 12, minH: 2, maxH: 8 },
      { i: "b", x: 4, y: 0, w: 8, h: 5, minW: 3, maxW: 12, minH: 2, maxH: 8 },
    ]);

    const next = fromLayout(ws, [
      { i: "b", x: 0, y: 3, w: 12, h: 4 },
      { i: "a", x: 2, y: 0, w: 6, h: 3 },
    ]);
    expect(placements(next)).toEqual([
      { id: "a", x: 2, y: 0, w: 6, h: 3 },
      { id: "b", x: 0, y: 3, w: 12, h: 4 },
    ]);
    expect(next[0]).toEqual({ ...ws[0], x: 2, w: 6 });
  });

  // Bug: a widget the library has not laid out yet loses its placement, or the whole write crashes.
  it("fromLayoutKeepsAWidgetWithNoItem", () => {
    const ws = [place("a", 0, 0, 4, 3), place("b", 4, 0, 4, 3)];
    expect(fromLayout(ws, [{ i: "a", x: 0, y: 3, w: 4, h: 3 }])[1]).toBe(ws[1]);
  });

  // Bug: a dashboard with a moved widget compares equal and the change is never written, or a reorder goes unseen.
  it("placementsEqualComparesIdsInOrderAndEveryNumber", () => {
    const ws = [place("a", 0, 0, 4, 3), place("b", 4, 0, 4, 3)];
    expect(placementsEqual(ws, [place("a", 0, 0, 4, 3), place("b", 4, 0, 4, 3)])).toBe(true);
    expect(placementsEqual(ws, [ws[1], ws[0]])).toBe(false);
    expect(placementsEqual(ws, [{ ...ws[0], id: "z" }, ws[1]])).toBe(false);
    expect(placementsEqual(ws, [ws[0]])).toBe(false);
    for (const changed of [{ x: 1 }, { y: 1 }, { w: 5 }, { h: 4 }]) {
      expect(placementsEqual(ws, [{ ...ws[0], ...changed }, ws[1]])).toBe(false);
    }
  });

  // Bug: a stored dashboard with an empty band of rows draws a hole the reader cannot drag a widget into.
  it("compactWidgetsClosesVerticalGaps", () => {
    const ws = [place("a", 0, 0, 6, 3), place("b", 6, 6, 6, 3), place("c", 0, 9, 12, 2)];
    expect(placements(compactWidgets(ws))).toEqual([
      { id: "a", x: 0, y: 0, w: 6, h: 3 },
      { id: "b", x: 6, y: 0, w: 6, h: 3 },
      { id: "c", x: 0, y: 3, w: 12, h: 2 },
    ]);
  });

  // Bug: the stacked column or the keyboard move follows array order instead of what the reader sees.
  it("readingOrderSortsByRowThenColumnThenId", () => {
    const ws = [place("d", 0, 5, 12, 2), place("c", 6, 0, 6, 3), place("b", 0, 0, 3, 3), place("a", 0, 0, 3, 3)];
    expect(readingOrder(ws).map((w) => w.id)).toEqual(["a", "b", "c", "d"]);
    expect(ws.map((w) => w.id)).toEqual(["d", "c", "b", "a"]);
  });

  // Bug: a keyboard move leaves the widget where it was, overlaps a neighbour the server refuses, or pushes a wide
  // widget past column 12.
  it("moveWidgetSwapsWithTheNeighbourClampsAndCompacts", () => {
    const row = [place("a", 0, 0, 4, 3), place("b", 4, 0, 4, 3), place("c", 8, 0, 4, 3)];
    expect(placements(moveWidget(row, "a", "later"))).toEqual([
      { id: "a", x: 4, y: 0, w: 4, h: 3 },
      { id: "b", x: 0, y: 0, w: 4, h: 3 },
      { id: "c", x: 8, y: 0, w: 4, h: 3 },
    ]);
    expect(placements(moveWidget(row, "c", "earlier"))).toEqual([
      { id: "a", x: 0, y: 0, w: 4, h: 3 },
      { id: "b", x: 8, y: 0, w: 4, h: 3 },
      { id: "c", x: 4, y: 0, w: 4, h: 3 },
    ]);

    const wide = [place("tile", 8, 0, 4, 3), place("table", 0, 3, 12, 5)];
    const moved = moveWidget(wide, "tile", "later");
    expect(placements(moved)).toEqual([
      { id: "tile", x: 0, y: 5, w: 4, h: 3 },
      { id: "table", x: 0, y: 0, w: 12, h: 5 },
    ]);
    expect(readingOrder(moved).map((w) => w.id)).toEqual(["table", "tile"]);
    expect(wide[1].x).toBe(0);
    expect(wide[0].y).toBe(0);

    expect(moveWidget(row, "a", "earlier")).toBe(row);
    expect(moveWidget(row, "c", "later")).toBe(row);
  });

  // Bug: on the default dashboard "Move later" on p95 latency and "Move earlier" on Tokens leave the widget where it was
  // in reading order and open a hole, because a swap across rows of different widths overlaps and compaction undoes it.
  it("everyEnabledMoveOnTheDefaultDashboardSwapsTheNeighboursAndStaysCompact", () => {
    const ws = DEFAULT_DASHBOARD.definition.widgets;
    const before = readingOrder(ws).map((w) => w.id);
    const overlap = (a: Placement, b: Placement) =>
      a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    for (const [i, id] of before.entries()) {
      for (const dir of ["earlier", "later"] as const) {
        const j = dir === "earlier" ? i - 1 : i + 1;
        if (j < 0 || j >= before.length) continue;
        const moved = moveWidget(ws, id, dir);
        const expected = [...before];
        [expected[i], expected[j]] = [expected[j], expected[i]];
        expect(readingOrder(moved).map((w) => w.id), `${id} ${dir}`).toEqual(expected);
        expect(placements(compactWidgets(moved)), `${id} ${dir} is compact`).toEqual(placements(moved));
        for (const a of moved) {
          expect(a.x + a.w, `${a.id} inside the grid`).toBeLessThanOrEqual(12);
          for (const b of moved) if (a !== b) expect(overlap(a, b), `${a.id} over ${b.id}`).toBe(false);
        }
        expect(moved.map((w) => w.id)).toEqual(ws.map((w) => w.id));
      }
    }
  });

  // Bug: a new table saved at a tile's size, or a new widget dropped over an existing one.
  it("placementForNewSizesByChartAndLandsAtTheBottom", () => {
    const ws = [place("a", 0, 0, 4, 3), place("b", 0, 3, 12, 5), place("c", 4, 0, 8, 2)];
    expect(placementForNew(ws, "number")).toEqual({ x: 0, y: 8, w: 4, h: 3 });
    expect(placementForNew(ws, "timeseries")).toEqual({ x: 0, y: 8, w: 6, h: 5 });
    expect(placementForNew(ws, "table")).toEqual({ x: 0, y: 8, w: 12, h: 5 });
    expect(placementForNew([], "number")).toEqual({ x: 0, y: 0, w: 4, h: 3 });
  });

  // Bug: a placement outside the validator's bounds reaches the server and the whole dashboard save is refused.
  it("clampPlacementKeepsEveryNumberInsideTheGrid", () => {
    expect(clampPlacement({ x: 11, y: -2, w: 1, h: 1 })).toEqual({ x: 9, y: 0, w: 3, h: 2 });
    expect(clampPlacement({ x: -1, y: 4, w: 20, h: 12 })).toEqual({ x: 0, y: 4, w: 12, h: 8 });
    expect(clampPlacement({ x: 6, y: 2, w: 6, h: 4 })).toEqual({ x: 6, y: 2, w: 6, h: 4 });
  });
});
