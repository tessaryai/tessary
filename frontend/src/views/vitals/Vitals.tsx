// SPDX-License-Identifier: Apache-2.0
/*
 * Vitals — the pulse strip's full-size page.
 *
 * A roll-up and nothing else: what the project spent, how many traces ran, how long they took and
 * how many tokens they used over the window, then the same broken down by call site or by model.
 * No comparison with an earlier window and no tint. The server still sends deltas and `flagged`,
 * and this page deliberately reads neither: a moved number is something to open a case about, not
 * something to color here.
 *
 * Row click → Traces filtered to the call site (`?call_site=`). The unpriced-models caveat is a
 * footnote linking Settings → Models; cache/token economics stay org-level in Settings.
 */
import { useMemo, useState, type ReactNode } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { useTenant } from "../../tenant/TenantContext";
import {
  Button,
  ErrorNote,
  PageHeader,
  SegmentedControl,
  Skeleton,
  Table,
  TableSkeleton,
  TBody,
  TD,
  TH,
  THead,
  TR,
} from "../../ui";
import type { Vitals as VitalsData, VitalsGroup } from "../../api/types";
import { usd } from "../../lib/usd";

type Days = "7" | "30";
type Dimension = "call_site" | "model";

/** Rows shown before "Show all". */
const COLLAPSED_ROWS = 8;

const COMPACT = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 1 });

function seconds(ms: number | null | undefined): string {
  return ms == null ? "—" : `${(ms / 1000).toFixed(1)}s`;
}

function count(n: number): string {
  return n.toLocaleString("en-US");
}

/** Per-trace spend is usually a fraction of a cent, which `usd`'s two places would round to $0.00. */
function perTrace(n: number | null | undefined): string {
  if (n == null) return "—";
  return n >= 1 ? usd(n) : `$${n.toFixed(3)}`;
}

function name(g: VitalsGroup): string {
  return g.label ?? g.key ?? "unattributed";
}

type Column = {
  key: string;
  label: string;
  numeric: boolean;
  sortValue: (g: VitalsGroup) => string | number;
  render: (g: VitalsGroup, total: number) => ReactNode;
};

/** Spend as a share-of-total bar beside the figure: where the money goes reads without reading numbers. */
function SpendCell({ value, total }: { value: number; total: number }) {
  const share = total > 0 ? Math.max(1, Math.round((value / total) * 100)) : 0;
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 h-1.5 rounded-micro bg-raised" aria-hidden="true">
        <div className="h-1.5 rounded-micro bg-muted" style={{ width: `${share}%` }} />
      </div>
      <span className="w-16 text-right">{usd(value)}</span>
    </div>
  );
}

const SPEND: Column = {
  key: "spend",
  label: "Spend",
  numeric: false,
  sortValue: (g) => g.cost.usd,
  render: (g, total) => <SpendCell value={g.cost.usd} total={total} />,
};

const COLUMNS: Record<Dimension, Column[]> = {
  call_site: [
    { key: "name", label: "Call site", numeric: false, sortValue: (g) => g.label ?? g.key ?? "", render: name },
    SPEND,
    {
      key: "per_trace",
      label: "Per trace",
      numeric: true,
      sortValue: (g) => g.cost.usd_per_turn ?? Number.NEGATIVE_INFINITY,
      render: (g) => perTrace(g.cost.usd_per_turn),
    },
    {
      key: "p95",
      label: "p95",
      numeric: true,
      sortValue: (g) => g.duration.p95_ms ?? Number.NEGATIVE_INFINITY,
      render: (g) => seconds(g.duration.p95_ms),
    },
    {
      key: "traces",
      label: "Traces",
      numeric: true,
      sortValue: (g) => g.duration.turns,
      render: (g) => count(g.duration.turns),
    },
  ],
  // A trace's root span carries no model, so latency and trace counts have no per-model split.
  model: [
    { key: "name", label: "Model", numeric: false, sortValue: (g) => g.label ?? g.key ?? "", render: name },
    SPEND,
    {
      key: "llm_spans",
      label: "LLM spans",
      numeric: true,
      sortValue: (g) => g.cost.calls,
      render: (g) => count(g.cost.calls),
    },
    {
      key: "tokens",
      label: "Tokens",
      numeric: true,
      sortValue: (g) => g.cost.tokens,
      render: (g) => COMPACT.format(g.cost.tokens),
    },
  ],
};

type Sort = { key: string; dir: "asc" | "desc" };

const BY_SPEND: Sort = { key: "spend", dir: "desc" };

function Total({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="bg-surface py-5 px-5 flex flex-col gap-2">
      <div className="text-label uppercase text-muted">{label}</div>
      <div className="font-mono text-metric text-fg tabular-nums">{value}</div>
      {note && <div className="text-small text-muted">{note}</div>}
    </div>
  );
}

const TOTALS_GRID = { gridTemplateColumns: "repeat(auto-fit, minmax(13rem, 1fr))" };

function Totals({ data }: { data: VitalsData }) {
  const { cost, duration } = data.total;
  const unfinished = duration.unterminated ?? 0;
  return (
    <section
      aria-label="Totals"
      className="grid gap-px bg-border border border-border rounded-card overflow-hidden"
      style={TOTALS_GRID}
    >
      <Total
        label="Spend"
        value={usd(cost.usd)}
        note={cost.usd_per_turn == null ? undefined : `${perTrace(cost.usd_per_turn)} per trace`}
      />
      <Total
        label="Traces"
        value={count(duration.turns)}
        note={unfinished > 0 ? `${count(unfinished)} did not finish` : undefined}
      />
      <Total
        label="p95 latency"
        value={seconds(duration.p95_ms)}
        note={duration.p50_ms == null ? undefined : `median ${seconds(duration.p50_ms)}`}
      />
      <Total label="Tokens" value={COMPACT.format(cost.tokens)} note={`across ${count(cost.calls)} LLM spans`} />
    </section>
  );
}

function VitalsLoading() {
  return (
    <div role="status" aria-label="Loading vitals">
      <div className="grid gap-px bg-border border border-border rounded-card overflow-hidden" style={TOTALS_GRID}>
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="bg-surface py-5 px-5">
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-8 w-28 mt-3" />
            <Skeleton className="h-3 w-24 mt-2.5" />
          </div>
        ))}
      </div>
      <TableSkeleton rows={COLLAPSED_ROWS} cols={5} className="mt-8" />
    </div>
  );
}

export function Vitals() {
  const { orgSlug, projectSlug, api } = useTenant();
  const navigate = useNavigate();
  const base = `/orgs/${orgSlug}/projects/${projectSlug}`;

  const [days, setDays] = useState<Days>("7");
  const [by, setBy] = useState<Dimension>("call_site");
  const [sort, setSort] = useState<Sort>(BY_SPEND);
  const [showAll, setShowAll] = useState(false);

  const vitalsQ = useQuery({
    queryKey: ["vitals", api.base, days, by],
    queryFn: () => api.getVitals(Number(days), by),
    placeholderData: keepPreviousData,
  });

  const columns = COLUMNS[by];

  const sorted = useMemo(() => {
    const data = vitalsQ.data?.groups ?? [];
    const col = columns.find((c) => c.key === sort.key) ?? SPEND;
    const flip = sort.dir === "asc" ? 1 : -1;
    return [...data].sort((a, b) => {
      const va = col.sortValue(a);
      const vb = col.sortValue(b);
      const d = typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb));
      return d * flip;
    });
  }, [vitalsQ.data, sort, columns]);

  const rows = showAll ? sorted : sorted.slice(0, COLLAPSED_ROWS);

  const toggleSort = (col: Column) => {
    setSort((prev) => {
      // First click: biggest first for numbers, A→Z for names.
      if (prev.key !== col.key) return { key: col.key, dir: col.key === "name" ? "asc" : "desc" };
      return { key: col.key, dir: prev.dir === "desc" ? "asc" : "desc" };
    });
  };

  const changeBy = (next: Dimension) => {
    setBy(next);
    setSort(BY_SPEND);
  };

  const openTraces = (callSite: string | null) => {
    // Only call sites filter Traces, and the unattributed group IS the absence of one.
    if (by !== "call_site" || !callSite) return;
    navigate(`${base}/traces?call_site=${encodeURIComponent(callSite)}`);
  };

  const data = vitalsQ.data;
  const linked = (g: VitalsGroup) => by === "call_site" && g.key != null;

  return (
    <div className="pt-9 px-10 pb-14">
      <PageHeader
        kicker="Monitor"
        title="Vitals"
        subtitle={`Last ${days} days`}
        actions={
          <SegmentedControl
            ariaLabel="Window"
            value={days}
            onChange={setDays}
            options={[
              { value: "7", label: "7 days" },
              { value: "30", label: "30 days" },
            ]}
          />
        }
      />

      {vitalsQ.isPending && <VitalsLoading />}
      {vitalsQ.isError && <ErrorNote error={vitalsQ.error} />}

      {data && (
        <>
          <Totals data={data} />

          <section aria-labelledby="vitals-breakdown" className="mt-8">
            <div className="flex flex-wrap items-end justify-between gap-3 mb-3">
              <div>
                <h2 id="vitals-breakdown" className="text-h3 text-fg">
                  {by === "call_site" ? "By call site" : "By model"}
                </h2>
                <p className="text-small text-muted">
                  {by === "call_site"
                    ? "Select a row to open its traces."
                    : "Latency has no per-model view, because a trace has no single model."}
                </p>
              </div>
              <SegmentedControl
                ariaLabel="Group by"
                value={by}
                onChange={changeBy}
                options={[
                  { value: "call_site", label: "By call site" },
                  { value: "model", label: "By model" },
                ]}
              />
            </div>

            <Table style={{ minWidth: 720 }}>
              <THead>
                <tr>
                  {columns.map((col) => {
                    const active = sort.key === col.key;
                    return (
                      <TH
                        key={col.key}
                        className={col.numeric ? "text-right" : undefined}
                        style={col.key === "spend" ? { width: "40%" } : undefined}
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
                      Nothing ran in this window. Vitals counts the last {data.window.days} days. Older traces are
                      in Traces.
                    </TD>
                  </TR>
                )}
                {rows.map((row) => (
                  <TR
                    key={row.key ?? "unattributed"}
                    interactive={linked(row)}
                    tabIndex={linked(row) ? 0 : undefined}
                    onClick={() => openTraces(row.key ?? null)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        openTraces(row.key ?? null);
                      }
                    }}
                    aria-label={linked(row) ? `Open traces for ${row.key}` : undefined}
                  >
                    {columns.map((col) => (
                      <TD
                        key={col.key}
                        className={
                          col.key === "name"
                            ? row.key == null
                              ? "font-mono text-small text-muted"
                              : "font-mono text-small"
                            : col.numeric
                              ? "font-mono text-small text-right text-muted"
                              : "font-mono text-small"
                        }
                        style={{ fontVariantNumeric: "tabular-nums", verticalAlign: "middle" }}
                      >
                        {col.render(row, data.total.cost.usd)}
                      </TD>
                    ))}
                  </TR>
                ))}
              </TBody>
            </Table>

            {sorted.length > COLLAPSED_ROWS && (
              <Button size="sm" className="mt-3" onClick={() => setShowAll((v) => !v)}>
                {showAll ? "Show fewer" : `Show all ${sorted.length}`}
              </Button>
            )}
          </section>

          {data.total.cost.unpriced_calls > 0 && (
            <p className="text-muted mt-6 pt-4 border-t border-border text-small">
              {count(data.total.cost.unpriced_calls)} LLM spans ran on a model with no price on file. Their spend
              is left out, not counted as free.{" "}
              <Link to={`${base}/settings/models`} className="text-link hover:text-link-hover hover:underline">
                Review model settings
              </Link>
              .
            </p>
          )}
        </>
      )}
    </div>
  );
}
