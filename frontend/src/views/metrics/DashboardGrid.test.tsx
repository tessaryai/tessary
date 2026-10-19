// SPDX-License-Identifier: Apache-2.0
/*
 * The dashboard grid on the real react-grid-layout at the 520 px jsdom width. No drag is simulated: the library's
 * layout callback is reached through mount-time compaction of a gapped layout, and the keyboard moves go through the
 * widget menu. The placement arithmetic itself is tested in gridLayout.test.ts.
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { METRICS_CATALOG, widget, WINDOW_7D } from "../../test/metricsFixtures";
import { DashboardGrid } from "./DashboardGrid";
import { moveWidget } from "./gridLayout";
import { viewOf } from "./metricsRules";
import type { MetricWidgetDefinition } from "./wire";
import { WidgetCard } from "./WidgetCard";

/** Three tiles on one row, then a full-width table: already compact, so mounting writes nothing. */
const COMPACT = [
  widget({ id: "a", title: "A", x: 0, y: 0, w: 4, h: 3 }),
  widget({ id: "b", title: "B", x: 4, y: 0, w: 4, h: 3 }),
  widget({ id: "c", title: "C", x: 8, y: 0, w: 4, h: 3 }),
  widget({ id: "d", title: "D", chart: "table", dimension: "model", x: 0, y: 3, w: 12, h: 5 }),
];

/** B sits six rows down with nothing above it, so the vertical compactor lifts it to the top. */
const GAPPED = [
  widget({ id: "a", title: "A", x: 0, y: 0, w: 6, h: 3 }),
  widget({ id: "b", title: "B", x: 6, y: 6, w: 6, h: 3 }),
];

function renderGrid(widgets: MetricWidgetDefinition[], over: { editing?: boolean; stacked?: boolean } = {}) {
  const handlers = { onPlacements: vi.fn(), onEdit: vi.fn(), onRemove: vi.fn() };
  const view = render(grid(widgets, over, handlers));
  return { ...handlers, rerender: (next: { editing?: boolean; stacked?: boolean }) => view.rerender(grid(widgets, next, handlers)) };
}

function grid(
  widgets: MetricWidgetDefinition[],
  over: { editing?: boolean; stacked?: boolean },
  handlers: { onPlacements: () => void; onEdit: () => void; onRemove: () => void },
): ReactNode {
  return (
    <DashboardGrid
      widgets={widgets}
      editing={over.editing ?? false}
      stacked={over.stacked ?? false}
      renderWidget={(w, menu) => (
        <WidgetCard
          widget={w}
          view={viewOf(METRICS_CATALOG, w.view)}
          state={{ status: "loading" }}
          window={WINDOW_7D}
          basePath="/orgs/acme/projects/default"
          callSite={null}
          editing={over.editing ?? false}
          menu={menu}
        />
      )}
      {...handlers}
    />
  );
}

const titles = () => screen.getAllByRole("region").map((r) => r.getAttribute("aria-label"));
const openMenu = (title: string) => fireEvent.click(screen.getByRole("button", { name: `${title} actions` }));

describe("DashboardGrid", () => {
  // Bug: a phone reader gets the desktop grid, widgets out of reading order, or edit menus on a read-only column.
  it("stackedRendersEveryWidgetInReadingOrderWithoutMenus", () => {
    const { onPlacements } = renderGrid([COMPACT[3], COMPACT[2], COMPACT[0], COMPACT[1]], { stacked: true, editing: true });

    expect(titles()).toEqual(["A", "B", "C", "D"]);
    expect(screen.queryByRole("button", { name: /actions$/ })).toBeNull();
    expect(screen.getByRole("region", { name: "A" }).closest(".react-grid-layout")).toBeNull();
    expect(onPlacements).not.toHaveBeenCalled();
  });

  // Bug: a page first drawn below lg, or that crosses lg while open, keeps the grid at the 520 px fallback width
  // because the width hook measured no box on its first render and never looks again.
  it("aGridFirstDrawnStackedTakesTheBoxWidthOnceUnstacked", () => {
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1000);
    const { rerender } = renderGrid(COMPACT, { stacked: true });

    rerender({ stacked: false });

    // (1000 - 11 gaps of 16) / 12 columns = 68.67 px; four of them and three gaps is 322.67 px.
    const item = screen.getByRole("region", { name: "A" }).parentElement!;
    expect(item.style.width).toBe("323px");
  });

  // Bug: a viewer's visit rewrites a stored dashboard's placements, or shows edit chrome.
  it("readModeRendersTheGridWithoutMenusAndIgnoresMountCompaction", () => {
    const { onPlacements } = renderGrid(GAPPED);

    expect(titles()).toEqual(["A", "B"]);
    expect(screen.queryByRole("button", { name: /actions$/ })).toBeNull();
    const grid = screen.getByRole("region", { name: "A" }).closest(".react-grid-layout")!;
    expect(grid.classList.contains("metrics-grid")).toBe(true);
    expect(grid.classList.contains("metrics-grid-editing")).toBe(false);
    // The library renders the handle either way; these two classes are what src/index.css hides it by.
    const corners = [...grid.querySelectorAll(".metrics-resize-corner")];
    expect(corners.map((c) => c.parentElement!.classList.contains("react-resizable-hide"))).toEqual([true, true]);
    expect(onPlacements).not.toHaveBeenCalled();
  });

  // Bug: the draft keeps a gap the grid no longer draws, so "Done" saves placements the reader never saw.
  it("editModeCompactsOnMountAndReportsThePlacements", () => {
    const { onPlacements } = renderGrid(GAPPED, { editing: true });

    expect(onPlacements).toHaveBeenCalled();
    expect(onPlacements.mock.lastCall![0]).toEqual([GAPPED[0], { ...GAPPED[1], y: 0 }]);
    const grid = screen.getByRole("region", { name: "A" }).closest(".react-grid-layout")!;
    expect(grid.classList.contains("metrics-grid-editing")).toBe(true);
    const corners = [...grid.querySelectorAll(".metrics-resize-corner")];
    expect(corners.map((c) => c.parentElement!.classList.contains("react-resizable-hide"))).toEqual([false, false]);
  });

  // Bug: "Move later" moves the wrong widget, or offers a move past either end of the dashboard.
  it("moveLaterSwapsPlacementsThroughTheMenu", () => {
    const { onPlacements } = renderGrid(COMPACT, { editing: true });
    expect(onPlacements).not.toHaveBeenCalled();

    openMenu("A");
    expect((screen.getByRole("menuitem", { name: "Move earlier" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("menuitem", { name: "Move later" }));
    expect(onPlacements).toHaveBeenCalledTimes(1);
    expect(onPlacements.mock.lastCall![0]).toEqual(moveWidget(COMPACT, "a", "later"));

    openMenu("D");
    expect((screen.getByRole("menuitem", { name: "Move later" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("menuitem", { name: "Move earlier" }));
    expect(onPlacements.mock.lastCall![0]).toEqual(moveWidget(COMPACT, "d", "earlier"));
  });

  // Bug: the menu's Edit or Remove reaches another widget's id.
  it("editAndRemoveReachTheirHandlers", () => {
    const { onEdit, onRemove } = renderGrid(COMPACT, { editing: true });

    openMenu("B");
    fireEvent.click(screen.getByRole("menuitem", { name: "Edit" }));
    expect(onEdit).toHaveBeenCalledWith("b");
    openMenu("C");
    fireEvent.click(screen.getByRole("menuitem", { name: "Remove" }));
    expect(onRemove).toHaveBeenCalledWith("c");
    expect(onEdit).toHaveBeenCalledTimes(1);
  });

  // Bug: the grid's children follow the stored array, so after a drag or a move the Tab and screen-reader order no
  // longer match the layout on screen; or a widget dropped, or rendered under another widget's title.
  it("rendersTheWidgetsInReadingOrderWithTheirTitlesAsSections", () => {
    renderGrid([COMPACT[3], COMPACT[0], COMPACT[2], COMPACT[1]], { editing: true });

    expect(titles()).toEqual(["A", "B", "C", "D"]);
    for (const t of ["A", "B", "C", "D"]) {
      expect(within(screen.getByRole("region", { name: t })).getByRole("button", { name: `${t} actions` })).toBeTruthy();
    }
  });
});
