// SPDX-License-Identifier: Apache-2.0
// The server ranks keys by the first measure, descending, with the fold last, so that is the order before any click.
import { useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Button, cn, Table, TBody, TD, TH, THead, TR } from "../../../ui";
import { metricsLink, OTHER_KEY, UNATTRIBUTED_KEY } from "../metricsLinks";
import { formatValue, measureOf } from "../metricsRules";
import type { MetricsRow, MetricWidgetDefinition } from "../wire";
import type { WidgetBodyProps } from "./NumberWidget";

/** Rows shown before "Show all": what an h5 card holds at 72 px rows. */
const COLLAPSED_ROWS = 6;

type Column = { key: string; label: string; numeric: boolean; share: boolean };
type Sort = { key: string; dir: "asc" | "desc" };

const DIMENSION = "__dimension__";

const nameOf = (r: MetricsRow) => r.label ?? r.key ?? "";

/** Missing values sort last in both directions, as the server ranks them. */
function compare(a: MetricsRow, b: MetricsRow, sort: Sort): number {
  const flip = sort.dir === "asc" ? 1 : -1;
  if (sort.key === DIMENSION) return nameOf(a).localeCompare(nameOf(b)) * flip;
  const va = a.values[sort.key];
  const vb = b.values[sort.key];
  if (va == null || vb == null) return (va == null ? 1 : 0) - (vb == null ? 1 : 0);
  return (va - vb) * flip;
}

/** The card's title-bar caption: only call-site rows open Traces. */
export function tableCaption(widget: MetricWidgetDefinition): string | undefined {
  return widget.dimension === "call_site" && widget.view !== "cases" ? "Select a row to open its traces" : undefined;
}

/** Spend as a share-of-total bar beside the figure: where the money goes reads without reading numbers. */
function ShareCell({ value, total, text }: { value: number; total: number; text: string }) {
  const share = total > 0 ? Math.max(1, Math.round((value / total) * 100)) : 0;
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 h-1.5 rounded-micro bg-raised" aria-hidden="true">
        <div className="h-1.5 rounded-micro bg-muted" style={{ width: `${share}%` }} />
      </div>
      <span className="w-16 text-right">{text}</span>
    </div>
  );
}

/** The companion a figure leaves out, under the cell that leaves it out. */
function subLine(measure: string, row: MetricsRow): string | null {
  const unpriced = row.values.unpriced_calls ?? row.values.unpriced_traces ?? 0;
  if (measure === "cost_usd" && unpriced > 0) return `${unpriced.toLocaleString("en-US")} unpriced`;
  const unfinished = row.values.unterminated_traces ?? 0;
  if (measure.startsWith("latency_") && unfinished > 0) return `${unfinished.toLocaleString("en-US")} did not finish`;
  return null;
}

export function TableWidget({ widget, view, result, window, basePath, editing }: WidgetBodyProps) {
  const navigate = useNavigate();
  const [sort, setSort] = useState<Sort>({ key: widget.measures[0], dir: "desc" });
  const [showAll, setShowAll] = useState(false);

  const shareKey = widget.measures.find((m) => measureOf(view, m)?.unit === "usd");
  const columns: Column[] = [
    {
      key: DIMENSION,
      label: view?.dimensions.find((d) => d.name === widget.dimension)?.label ?? String(widget.dimension),
      numeric: false,
      share: false,
    },
    ...widget.measures.map((m) => ({ key: m, label: measureOf(view, m)?.label ?? m, numeric: m !== shareKey, share: m === shareKey })),
  ];
  const unitOf = (m: string) => measureOf(view, m)?.unit ?? "count";

  const named = result.rows.filter((r) => r.key !== OTHER_KEY);
  const sorted = [...named.sort((a, b) => compare(a, b, sort)), ...result.rows.filter((r) => r.key === OTHER_KEY)];
  const rows = showAll ? sorted : sorted.slice(0, COLLAPSED_ROWS);
  const shareTotal = shareKey ? result.rows.reduce((sum, r) => sum + (r.values[shareKey] ?? 0), 0) : 0;

  const toggleSort = (col: Column) =>
    setSort((prev) =>
      prev.key === col.key
        ? { key: col.key, dir: prev.dir === "desc" ? "asc" : "desc" }
        : { key: col.key, dir: col.key === DIMENSION ? "asc" : "desc" },
    );

  const cell = (col: Column, row: MetricsRow): ReactNode => {
    if (col.key === DIMENSION) return nameOf(row);
    const v = row.values[col.key];
    const text = formatValue(unitOf(col.key), v);
    const sub = subLine(col.key, row);
    return (
      <>
        {col.share ? <ShareCell value={v ?? 0} total={shareTotal} text={text} /> : text}
        {sub && <div className="text-small text-subtle">{sub}</div>}
      </>
    );
  };

  return (
    <div className="h-full min-h-0 overflow-auto">
      <Table>
        <THead>
          <tr>
            {columns.map((col) => {
              const active = sort.key === col.key;
              return (
                <TH
                  key={col.key}
                  className={col.numeric ? "text-right" : undefined}
                  style={col.share ? { width: "40%" } : undefined}
                  aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}
                >
                  <button
                    type="button"
                    onClick={() => toggleSort(col)}
                    className="cursor-pointer hover:text-fg transition-colors"
                    style={{ font: "inherit", letterSpacing: "inherit", color: "inherit" }}
                    title={`Sort by ${col.label}`}
                  >
                    {col.label}
                    {active && (
                      <span aria-hidden="true" className="font-mono ml-1">
                        {sort.dir === "desc" ? "↓" : "↑"}
                      </span>
                    )}
                  </button>
                </TH>
              );
            })}
          </tr>
        </THead>
        <TBody>
          {rows.length === 0 && (
            <TR>
              <TD colSpan={columns.length} className="text-muted py-4.5 px-3 text-small">
                Nothing ran in this window.
              </TD>
            </TR>
          )}
          {rows.map((row) => {
            const href = editing
              ? null
              : metricsLink({ basePath, view: widget.view, dimension: widget.dimension, key: row.key, window });
            const open = () => {
              if (href) navigate(href);
            };
            const quiet = row.key === OTHER_KEY || row.key === UNATTRIBUTED_KEY;
            return (
              <TR
                key={String(row.key)}
                interactive={href != null}
                tabIndex={href ? 0 : undefined}
                aria-label={href ? (widget.view === "cases" ? "Open Triage" : `Open traces for ${nameOf(row)}`) : undefined}
                onClick={open}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    open();
                  }
                }}
              >
                {columns.map((col) => (
                  <TD
                    key={col.key}
                    className={cn(
                      "font-mono text-small tabular-nums",
                      col.key === DIMENSION ? (quiet ? "text-muted" : "text-fg") : col.numeric ? "text-right text-muted" : "text-fg",
                    )}
                    style={{ verticalAlign: "middle" }}
                  >
                    {cell(col, row)}
                  </TD>
                ))}
              </TR>
            );
          })}
        </TBody>
      </Table>
      {sorted.length > COLLAPSED_ROWS && (
        <Button size="sm" className="mt-3" onClick={() => setShowAll((v) => !v)}>
          {showAll ? "Show fewer" : `Show all ${sorted.length}`}
        </Button>
      )}
    </div>
  );
}
