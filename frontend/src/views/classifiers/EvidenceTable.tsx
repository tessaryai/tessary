// SPDX-License-Identifier: Apache-2.0
/*
 * The population behind a finding, as a table of the spans it was measured over.
 *
 * <h2>Why spans and not traces</h2>
 * A `member` IS a span. Tool error enumerates one ref per CALL, so several refs routinely name the
 * same trace — and the list this replaced rendered each of them as its own link reading "Window member
 * trace →", which on one real finding printed the same trace id four times in a row and then did it
 * 688 times. Span rows say what actually differs between them: which call, when, how long, and whether
 * it errored.
 *
 * <h2>Empty cells are not missing data</h2>
 * Tokens and cost are properties of an LLM span. A tool span has neither, so those columns are blank
 * on every tool-error row by construction — which is why the column picker exists rather than a fixed
 * column set: a duration-drift finding fills them and a tool-error one never will.
 *
 * <h2>Ten rows, then more on demand</h2>
 * The set behind this can be 27,000 rows. The page reads ten, states the total beside them, and pages
 * on when asked — the count is what stops ten rows from reading as the whole population.
 */
import { useMemo, useState } from "react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type { EvidenceSpan } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, Table, TBody, TD, TH, THead, TR, TableSkeleton } from "../../ui";
import { cn } from "../../ui/cn";
import { VerbButton } from "./shared";

const PAGE = 10;

/** The detector's own vocabulary, spelled for a reader. */
const ROLE_LABEL: Record<string, string> = {
  exemplar: "Exemplar",
  member: "Member",
  baseline: "Baseline",
  witness: "Witness",
  changepoint: "Changepoint",
};

/**
 * What each role means, in one line, on hover. The words are load-bearing and not obvious: a reader
 * seeing 688 members and 3 witnesses should not have to guess which side of the comparison is which.
 */
const ROLE_HINT: Record<string, string> = {
  exemplar: "One instance the classifier surfaced as representative.",
  member: "The flagged population, enumerated: every row the claim was measured over.",
  baseline: "The reference side: what the flagged window was compared against.",
  witness: "The rows that carry the claim: the failures themselves.",
  changepoint: "Where the classifier says the behavior changed.",
};

type ColumnKey = "role" | "name" | "kind" | "status" | "started" | "latency" | "tokens" | "cost" | "model" | "trace";
type ColumnDef = { key: ColumnKey; label: string; numeric?: boolean };

/** Declaration order is display order. */
const COLUMNS: ColumnDef[] = [
  { key: "role", label: "Role" },
  { key: "name", label: "Name" },
  { key: "kind", label: "Kind" },
  { key: "status", label: "Status" },
  { key: "started", label: "Start time" },
  { key: "latency", label: "Latency (ms)", numeric: true },
  { key: "tokens", label: "Total tokens", numeric: true },
  { key: "cost", label: "Cost ($)", numeric: true },
  { key: "model", label: "Model" },
  { key: "trace", label: "Trace" },
];

const DEFAULT_VISIBLE: ColumnKey[] = ["role", "name", "status", "started", "latency", "trace"];

export function EvidenceTable({ findingId, basePath }: { findingId: string; basePath: string }) {
  const { api } = useTenant();
  const [role, setRole] = useState<string | null>(null);
  const [visible] = useState<Set<ColumnKey>>(new Set(DEFAULT_VISIBLE));

  const q = useInfiniteQuery({
    queryKey: ["behavior-finding-evidence", api.base, findingId, role],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      api.getBehaviorFindingEvidence(findingId, { role: role ?? undefined, limit: PAGE, cursor: pageParam }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: findingId !== "",
  });

  const columns = useMemo(() => COLUMNS.filter((c) => visible.has(c.key)), [visible]);
  const pages = q.data?.pages ?? [];
  const rows = pages.flatMap((p) => p.rows ?? []);
  // Recorded counts, not live ones: the question a footer answers is "how big is the claim", and a
  // ref whose substrate aged out was still part of what the detector measured.
  const recorded = pages[0]?.recordedCounts ?? {};
  const roles = Object.entries(recorded).filter(([, n]) => (n ?? 0) > 0);
  const total = roles.reduce((sum, [, n]) => sum + (n ?? 0), 0);
  const shown = role ? (recorded[role] ?? 0) : total;

  if (q.isError) return <ErrorNote error={q.error} />;
  if (q.isLoading) return <TableSkeleton rows={4} cols={columns.length} />;
  if (total === 0) {
    return (
      <p className="text-subtle mt-2 text-body" style={{ maxWidth: 560 }}>
        This finding cites no rows. Several classifiers compare against a fitted model rather than a
        stretch of traffic, so there is nothing to enumerate on the reference side.
      </p>
    );
  }

  return (
    <div className="mt-2 gap-2.5" style={{ display: "flex", flexDirection: "column" }}>
      {/* The role filter IS the reading. On a tool-error finding the three witnesses are the whole
          story and the 688 members are the denominator they are read against, and a reader who
          cannot separate them scrolls past the story. */}
      <div className="flex flex-wrap items-center gap-1.5">
        <RoleChip label={`All (${total.toLocaleString()})`} on={role === null} onClick={() => setRole(null)} />
        {roles.map(([r, n]) => (
          <RoleChip
            key={r}
            label={`${ROLE_LABEL[r] ?? r} (${(n ?? 0).toLocaleString()})`}
            title={ROLE_HINT[r]}
            on={role === r}
            onClick={() => setRole(r)}
          />
        ))}
      </div>

      <Table className="text-body" style={{ minWidth: Math.max(760, columns.length * 132) }}>
        <THead>
          <TR>
            {columns.map((col) => (
              <TH
                key={col.key}
                className={col.numeric ? "text-right" : undefined}
              >
                {col.label}
              </TH>
            ))}
          </TR>
        </THead>
        <TBody>
          {rows.map((row, i) => (
            <TR key={`${row.role}-${row.traceId ?? ""}-${row.spanId ?? ""}-${i}`}>
              {columns.map((col) => (
                <Cell key={col.key} col={col.key} row={row} basePath={basePath} />
              ))}
            </TR>
          ))}
        </TBody>
      </Table>

      <div className="flex flex-wrap items-center gap-2.5">
        <span className="text-subtle text-small">
          {rows.length.toLocaleString()} of {shown.toLocaleString()}
        </span>
        {q.hasNextPage && (
          <VerbButton kind="outline" disabled={q.isFetchingNextPage} onClick={() => void q.fetchNextPage()}>
            {q.isFetchingNextPage ? "Loading…" : "Load more"}
          </VerbButton>
        )}
      </div>
    </div>
  );
}

function RoleChip({
  label,
  title,
  on,
  onClick,
}: {
  label: string;
  title?: string;
  on: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      className={cn(
        "py-0.75 px-2.25 text-small",
        on
          ? "border border-border bg-surface text-fg transition-colors"
          : "border border-border text-muted hover:text-fg transition-colors",
      )}
      style={{ borderRadius: "var(--radius-control)" }}
    >
      {label}
    </button>
  );
}

/** A dash, not an empty cell: "this span has no tokens" and "this column failed to render" must differ. */
const NONE = <span className="text-subtle">—</span>;

function Cell({ col, row, basePath }: { col: ColumnKey; row: EvidenceSpan; basePath: string }) {
  const numeric = COLUMNS.find((c) => c.key === col)?.numeric;
  return (
    <TD
      className={cn("px-3 py-1.75", numeric && "text-right tabular-nums")}
      style={{ whiteSpace: "nowrap" }}
    >
      {render(col, row, basePath)}
    </TD>
  );
}

function render(col: ColumnKey, row: EvidenceSpan, basePath: string) {
  switch (col) {
    case "role":
      return (
        <span title={ROLE_HINT[row.role]} className="text-muted">
          {ROLE_LABEL[row.role] ?? row.role}
        </span>
      );
    case "name":
      return row.name ? <span className="font-mono">{row.name}</span> : NONE;
    case "kind":
      return row.kind ?? NONE;
    // Status carries the one bit a reader scans for on a tool-error finding, so it is coloured rather
    // than printed: three error rows in a page of successes should be findable without reading.
    case "status":
      return row.status ? (
        <span className={row.status === "error" || row.level === "ERROR" ? "text-error" : "text-muted"}>
          {row.errorType ? `${row.status} · ${row.errorType}` : row.status}
        </span>
      ) : (
        NONE
      );
    case "started":
      return row.startedAt ? new Date(row.startedAt).toLocaleString() : NONE;
    case "latency":
      return row.latencyMs != null ? row.latencyMs.toLocaleString() : NONE;
    case "tokens":
      return row.totalTokens != null ? row.totalTokens.toLocaleString() : NONE;
    case "cost":
      return row.totalCost != null ? row.totalCost.toFixed(4) : NONE;
    case "model":
      return row.model ?? NONE;
    case "trace":
      return row.traceId ? (
        <Link
          to={`${basePath}/traces/${encodeURIComponent(row.traceId)}`}
          className="font-mono text-link hover:text-link-hover transition-colors">
          {row.traceId.slice(0, 8)}…
        </Link>
      ) : (
        NONE
      );
    default:
      return NONE;
  }
}
