// SPDX-License-Identifier: Apache-2.0
/*
 * The dashboard's widgets on react-grid-layout, or one read-only column below lg. Read mode draws through the same
 * grid with drag and resize off, so a dashboard looks the same in both modes; the library compacts a gapped stored
 * layout on mount, which is drawn always and written into the draft only while editing.
 */
import type { ReactElement, ReactNode, Ref } from "react";
import { GridLayout, type Layout, type ResizeHandleAxis, verticalCompactor } from "react-grid-layout";
import { cn } from "../../ui";
import { useWidth } from "../components/chart/useWidth";
import { COLUMNS, fromLayout, GRID_MARGIN, moveWidget, placementsEqual, readingOrder, ROW_HEIGHT, toLayout } from "./gridLayout";
import type { MetricWidgetDefinition } from "./wire";
import { WidgetMenu } from "./WidgetMenu";

/** The library hands its DraggableCore this ref, so the handle must forward it. */
function resizeCorner(axis: ResizeHandleAxis, ref: Ref<HTMLElement>) {
  return (
    <span
      ref={ref as Ref<HTMLSpanElement>}
      aria-hidden="true"
      className={`react-resizable-handle react-resizable-handle-${axis} metrics-resize-corner`}
    >
      <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
        <path d="M14 6 6 14" />
        <path d="M14 10l-4 4" />
      </svg>
    </span>
  );
}

export function DashboardGrid({
  widgets,
  editing,
  stacked,
  renderWidget,
  onPlacements,
  onEdit,
  onRemove,
}: {
  widgets: MetricWidgetDefinition[];
  editing: boolean;
  /** True below lg: a single read-only column in reading order. */
  stacked: boolean;
  renderWidget: (w: MetricWidgetDefinition, menu: ReactNode | undefined) => ReactElement;
  /** Edit mode only: a drag, a resize, a move, or mount-time compaction. */
  onPlacements: (next: MetricWidgetDefinition[]) => void;
  onEdit: (id: string) => void;
  onRemove: (id: string) => void;
}) {
  const [boxRef, width] = useWidth();

  if (stacked) {
    return (
      <div className="flex flex-col gap-4">
        {readingOrder(widgets).map((w) => (
          <div key={w.id}>{renderWidget(w, undefined)}</div>
        ))}
      </div>
    );
  }

  const order = readingOrder(widgets).map((w) => w.id);
  const menuFor = (w: MetricWidgetDefinition, title: string) => {
    const at = order.indexOf(w.id);
    return (
      <WidgetMenu
        title={title}
        canEarlier={at > 0}
        canLater={at < order.length - 1}
        onEdit={() => onEdit(w.id)}
        onEarlier={() => onPlacements(moveWidget(widgets, w.id, "earlier"))}
        onLater={() => onPlacements(moveWidget(widgets, w.id, "later"))}
        onRemove={() => onRemove(w.id)}
      />
    );
  };

  const onLayoutChange = (layout: Layout) => {
    const next = fromLayout(widgets, layout);
    if (editing && !placementsEqual(next, widgets)) onPlacements(next);
  };

  return (
    <div ref={boxRef}>
      <GridLayout
        width={width}
        layout={toLayout(widgets)}
        gridConfig={{ cols: COLUMNS, rowHeight: ROW_HEIGHT, margin: GRID_MARGIN, containerPadding: [0, 0] }}
        dragConfig={{ enabled: editing, handle: ".metrics-drag-handle", cancel: "[data-no-drag]", bounded: true }}
        resizeConfig={{ enabled: editing, handles: ["se"], handleComponent: resizeCorner }}
        compactor={verticalCompactor}
        onLayoutChange={onLayoutChange}
        className={cn("metrics-grid", editing && "metrics-grid-editing")}
      >
        {widgets.map((w) => (
          <div key={w.id} className="min-w-0">
            {renderWidget(w, editing ? menuFor(w, w.title ?? w.id) : undefined)}
          </div>
        ))}
      </GridLayout>
    </div>
  );
}
