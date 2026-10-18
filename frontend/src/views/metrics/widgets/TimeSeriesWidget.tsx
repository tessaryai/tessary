// SPDX-License-Identifier: Apache-2.0
import { autoTitleOf } from "../editor/widgetForm";
import { TimeSeriesChart } from "../chart/TimeSeriesChart";
import { barsFor, chartHeightFor, measureOf, seriesOf } from "../metricsRules";
import type { MetricsQueryResult } from "../wire";
import type { WidgetBodyProps } from "./NumberWidget";

/** The fold note's line (text-small, 18 px) and the 8 px gap above it, taken from the chart so the card does not clip. */
const FOLD_NOTE_PX = 26;

const GRANULARITY_WORD = { hour: "Hourly", day: "Daily", week: "Weekly" };

/** The card's title-bar caption: buckets are UTC hours, days or weeks. */
export function timeSeriesCaption(result: MetricsQueryResult): string | undefined {
  const g = result.window.granularity;
  return g ? `${GRANULARITY_WORD[g]}, UTC` : undefined;
}

export function TimeSeriesWidget({ widget, view, result, window }: WidgetBodyProps) {
  const name = widget.measures[0];
  const measure = measureOf(view, name);
  const unit = measure?.unit ?? "count";
  const top = result.top_n;
  const folded = top != null && top.total_keys > top.shown;
  const dimension = view?.dimensions.find((d) => d.name === widget.dimension)?.label ?? widget.dimension;

  return (
    <div className="flex flex-col gap-2 min-w-0">
      <TimeSeriesChart
        title={widget.title ?? autoTitleOf(widget, view)}
        unit={unit}
        window={window}
        granularity={result.window.granularity}
        series={seriesOf(result, name, measure?.label ?? name)}
        height={chartHeightFor(widget.h) - (folded ? FOLD_NOTE_PX : 0)}
        bars={barsFor(unit)}
      />
      {folded && (
        <p className="text-small text-muted">
          {`Top ${top.shown} of ${top.total_keys} by ${dimension?.toLowerCase()}; the rest are in Other`}
        </p>
      )}
    </div>
  );
}
