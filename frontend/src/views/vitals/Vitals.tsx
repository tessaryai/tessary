// SPDX-License-Identifier: Apache-2.0
/*
 * Vitals — the pulse strip's full-size page.
 *
 * Deterministic, never judged. Two headline numbers (spend · p95 turn
 * latency, 7d vs prior 7d) over a by-call-site table. Amber
 * ONLY, never red: the hottest mover per numeric column wears a
 * warning-subtle tint — a future spend-spike detector would open cases
 * instead of coloring this page. Row click → Traces filtered to the call
 * site (`?call_site=`). Unpriced-models caveat is a footnote linking
 * Settings → Models; cache/token economics stay org-level in Settings.
 *
 * The tint comes from the server's own `flagged` flags, not a client-side max:
 * the hottest spend is the biggest MOVER, not the biggest number, and only the
 * read that holds both windows can tell those apart.
 */
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, PageHeader, Skeleton, Table, TableSkeleton, TBody, TD, TH, THead, TR, cn } from "../../ui";
import type { Vitals as VitalsData, VitalsGroup } from "../../api/types";

/** U+2212 minus — matches the sheet's `−3%` glyph, wider than a hyphen. */
const MINUS = "−";

function usd(n: number | null | undefined): string {
  if (n == null) return "—";
  return n >= 100 ? `$${Math.round(n)}` : `$${n.toFixed(2)}`;
}

function seconds(ms: number | null | undefined): string {
  return ms == null ? "—" : `${(ms / 1000).toFixed(1)}s`;
}

function signedPctOrDash(n: number | null | undefined): string {
  if (n == null) return "—";
  const rounded = Math.round(n);
  return rounded < 0 ? `${MINUS}${Math.abs(rounded)}%` : `+${rounded}%`;
}

type ColKey = "call_site" | "spend" | "delta_spend" | "p95" | "turns";

type Column = {
  key: ColKey;
  label: string;
  numeric: boolean;
  sortValue: (g: VitalsGroup) => string | number;
  format: (g: VitalsGroup) => string;
  /** Amber when the server flagged this metric as the window's mover. Turns never tints. */
  flagged?: (g: VitalsGroup) => boolean;
};

const COLUMNS: Column[] = [
  {
    key: "call_site",
    label: "Call site",
    numeric: false,
    sortValue: (g) => g.label ?? g.key ?? "",
    format: (g) => g.label ?? g.key ?? "unattributed",
  },
  {
    key: "spend",
    label: "Spend",
    numeric: true,
    sortValue: (g) => g.cost.usd,
    format: (g) => usd(g.cost.usd),
    flagged: (g) => g.cost.flagged,
  },
  {
    key: "delta_spend",
    label: "\u0394 spend / turn",
    numeric: true,
    sortValue: (g) => g.cost.delta_pct_per_turn ?? Number.NEGATIVE_INFINITY,
    format: (g) => signedPctOrDash(g.cost.delta_pct_per_turn),
    flagged: (g) => g.cost.flagged,
  },
  {
    key: "p95",
    label: "p95",
    numeric: true,
    sortValue: (g) => g.duration.p95_ms ?? Number.NEGATIVE_INFINITY,
    format: (g) => seconds(g.duration.p95_ms),
    flagged: (g) => g.duration.flagged,
  },
  {
    key: "turns",
    label: "Turns",
    numeric: true,
    sortValue: (g) => g.duration.turns,
    format: (g) => g.duration.turns.toLocaleString("en-US"),
  },
];

type Sort = { key: ColKey; dir: "asc" | "desc" };

/**
 * One headline stat. `delta` renders amber only when the server flagged the
 * metric — this page never decides on its own that a number is bad.
 */
function StatCard({
  label,
  value,
  delta,
  prior,
  flagged,
}: {
  label: string;
  value: string;
  delta: string;
  prior: string;
  flagged: boolean;
}) {
  return (
    <div className="bg-surface border border-border py-4.5 px-5" style={{ borderRadius: "var(--radius-card)" }}>
      <div className="text-label uppercase text-muted">{label}</div>
      <div className="flex items-baseline mt-2.5 gap-2.5">
        <span
          className="font-mono text-metric text-fg tabular-nums">
          {value}
        </span>
        <span className={cn("font-mono text-code", flagged ? "text-warning" : "text-muted")} >
          {delta}
        </span>
      </div>
      <div className="font-mono text-subtle mt-2 text-small">
        {prior}
      </div>
    </div>
  );
}

/**
 * The two headline cards, derived from the project total.
 *
 * Tool errors were the third and moved to the Classifiers page, where a moved failure rate arrives with
 * the error patterns that moved it. A bare percentage here could say that something changed but never
 * what, and two surfaces reporting one fact is how they come to disagree.
 */
function Headline({ data }: { data: VitalsData }) {
  const t = data.total;
  return (
    <div className="gap-3.5" style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)" }}>
      <StatCard
        label="Spend"
        value={usd(t.cost.usd)}
        delta={signedPctOrDash(t.cost.delta_pct_per_turn)}
        prior={t.cost.baseline_usd == null ? "no prior window" : `prior ${data.window.days}d ${usd(t.cost.baseline_usd)}`}
        flagged={t.cost.flagged}
      />
      <StatCard
        label="p95 turn latency"
        value={seconds(t.duration.p95_ms)}
        delta={signedPctOrDash(t.duration.delta_pct)}
        prior={
          t.duration.baseline_p95_ms == null
            ? "no prior window"
            : `prior ${data.window.days}d ${seconds(t.duration.baseline_p95_ms)}`
        }
        flagged={t.duration.flagged}
      />
    </div>
  );
}

function VitalsLoading() {
  return (
    <div role="status" aria-label="Loading vitals">
      <div className="gap-3.5" style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)" }}>
        {[0, 1].map((i) => (
          <div key={i} className="bg-surface border border-border py-4.5 px-5" style={{ borderRadius: "var(--radius-card)" }}>
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-8 w-28 mt-3"  />
            <Skeleton className="h-3 w-24 mt-2.5"  />
          </div>
        ))}
      </div>
      <TableSkeleton rows={6} cols={5} className="mt-8" />
    </div>
  );
}

export function Vitals() {
  const { orgSlug, projectSlug, api } = useTenant();
  const navigate = useNavigate();
  const base = `/orgs/${orgSlug}/projects/${projectSlug}`;

  const vitalsQ = useQuery({
    queryKey: ["vitals", api.base],
    queryFn: () => api.getVitals(7, "call_site"),
  });

  const [sort, setSort] = useState<Sort | null>(null);

  const rows = useMemo(() => {
    const data = vitalsQ.data?.groups ?? [];
    if (!sort) return data;
    const col = COLUMNS.find((c) => c.key === sort.key);
    if (!col) return data;
    const flip = sort.dir === "asc" ? 1 : -1;
    return [...data].sort((a, b) => {
      const va = col.sortValue(a);
      const vb = col.sortValue(b);
      const d = typeof va === "number" && typeof vb === "number" ? va - vb : String(va).localeCompare(String(vb));
      return d * flip;
    });
  }, [vitalsQ.data, sort]);

  const toggleSort = (col: Column) => {
    setSort((prev) => {
      // First click: biggest first for numerics, A→Z for the call-site column.
      if (!prev || prev.key !== col.key) return { key: col.key, dir: col.numeric ? "desc" : "asc" };
      return { key: col.key, dir: prev.dir === "desc" ? "asc" : "desc" };
    });
  };

  const openTraces = (callSite: string | null) => {
    // The unattributed group has no call site to filter by — it IS the absence of one.
    if (!callSite) return;
    navigate(`${base}/traces?call_site=${encodeURIComponent(callSite)}`);
  };

  const data = vitalsQ.data;

  return (
    <div className="pt-9 px-10 pb-14">
      <PageHeader
        kicker="Monitor"
        title="Vitals"
      />

      {vitalsQ.isPending && <VitalsLoading />}
      {vitalsQ.isError && <ErrorNote error={vitalsQ.error} />}

      {data && (
        <>
          <Headline data={data} />

          <div className="flex items-baseline gap-3 mt-7.5 mx-0 mb-2.5">
            <h2 className="font-mono text-label uppercase text-muted">By call site</h2>
            <span className="text-subtle text-small">
              {data.groups.length} · movers tinted
            </span>
          </div>

          <Table style={{ minWidth: 860 }}>
            <THead>
              <tr>
                {COLUMNS.map((col) => {
                  const active = sort?.key === col.key;
                  return (
                    <TH
                      key={col.key}
                      className={col.numeric ? "text-right" : undefined}
                      aria-sort={active ? (sort!.dir === "asc" ? "ascending" : "descending") : undefined}
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
                            {sort!.dir === "desc" ? "↓" : "↑"}
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
                  <TD colSpan={COLUMNS.length} className="text-subtle py-4.5 px-3 text-small">
                    Nothing ran in this window. Vitals counts the last {data.window.days} days. Older traces are
                    in Traces.
                  </TD>
                </TR>
              )}
              {rows.map((row) => (
                <TR
                  key={row.key ?? "unattributed"}
                  interactive={row.key != null}
                  tabIndex={row.key != null ? 0 : undefined}
                  onClick={() => openTraces(row.key ?? null)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter" || e.key === " ") {
                      e.preventDefault();
                      openTraces(row.key ?? null);
                    }
                  }}
                  aria-label={row.key ? `Open traces for ${row.key}` : undefined}
                >
                  {COLUMNS.map((col) => {
                    const hot = col.flagged?.(row) ?? false;
                    return (
                      <TD
                        key={col.key}
                        className={cn(
                          "font-mono text-small",
                          col.numeric && "text-right",
                          hot ? "text-warning bg-warning-subtle" : "text-fg",
                        )}
                        style={{ fontVariantNumeric: "tabular-nums" }}
                      >
                        {col.format(row)}
                      </TD>
                    );
                  })}
                </TR>
              ))}
            </TBody>
          </Table>

          {data.total.cost.unpriced_calls > 0 && (
            <p className="text-subtle mt-3 text-small">
              {data.total.cost.unpriced_calls.toLocaleString("en-US")} calls ran on a model with no price on file, so
              their spend is excluded rather than counted as free.{" "}
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
