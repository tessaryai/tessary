// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { Link } from "react-router-dom";
import type { VersionSummary, VersionUsage } from "../../api/types";
import { EmptyState, ErrorNote, SegmentedControl, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { formatCost, formatTokens, formatWhen } from "../traces/index-data";
import { LabelBadge } from "./promptBits";
import { formatVersionRef } from "./promptFolders";
import { usePromptUsage } from "./promptReads";

const WINDOWS = [
  { value: "1", label: "1 day" },
  { value: "7", label: "7 days" },
  { value: "30", label: "30 days" },
];

/** Milliseconds under a second, else seconds to one decimal: model calls sit on that boundary. */
function formatMs(ms: number | null): string {
  if (ms === null) return "—";
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

/** Where a row's version, its linked traces, and the fetch code live on the prompt page. */
export type UsageLinks = { version: (n: number) => string; traces: (n: number) => string; usePrompt: string };

const NUM = "whitespace-nowrap text-right font-mono tabular-nums";
const DATE = "whitespace-nowrap font-mono text-small text-muted";
const LINK = "text-link hover:text-link-hover";
const HEADERS = [
  "Version", "Deployment labels", "Calls", "Errors", "p50", "p95", "Input tokens", "Output tokens", "Cost",
  "First call in range", "Last call in range",
];
/** Calls through Cost are numbers, right-aligned. */
const isNumberColumn = (i: number) => i >= 2 && i <= 8;

function errorRate(errors: number, calls: number): string {
  if (errors === 0) return "0%";
  const pct = (errors / calls) * 100;
  return pct < 0.1 ? "<0.1%" : `${pct.toFixed(1)}%`;
}

/** A version of this prompt carries its labels and links; a version only spans name carries neither. */
type RowVersion = { label: string; labels?: string[]; number?: number };

function UsageRow({ row, usage, links }: { row: RowVersion; usage: VersionUsage | undefined; links: UsageLinks }) {
  const { label, labels, number } = row;
  const own = labels?.filter((l) => l !== "latest") ?? [];
  const leading = (
    <>
      <TD className="font-mono">
        {number === undefined ? label : <Link to={links.version(number)} className={LINK}>{label}</Link>}
      </TD>
      <TD>
        {labels === undefined ? null : labels.length === 0 ? (
          <span className="text-muted">No labels</span>
        ) : (
          <span className="flex flex-wrap gap-1">
            {own.map((l) => (
              <LabelBadge key={l} label={l} text={l} />
            ))}
            {labels.includes("latest") && <LabelBadge label="latest" text="latest" />}
          </span>
        )}
      </TD>
    </>
  );
  const traces = (
    <TD className="whitespace-nowrap text-right">
      {number !== undefined && (
        <Link to={links.traces(number)} className={LINK}>
          View traces
        </Link>
      )}
    </TD>
  );
  if (!usage) {
    return (
      <TR>
        {leading}
        <TD className={NUM}>0</TD>
        {Array.from({ length: 8 }, (_, i) => (
          <TD key={i} className={i < 6 ? NUM : DATE}>
            —
          </TD>
        ))}
        {traces}
      </TR>
    );
  }
  const unpriced = usage.unpriced_calls;
  const calls = usage.calls.toLocaleString("en-US");
  return (
    <TR>
      {leading}
      <TD className={NUM}>{calls}</TD>
      <TD className={NUM}>
        <span title={`${usage.error_calls.toLocaleString("en-US")} of ${calls} calls failed`}>
          {errorRate(usage.error_calls, usage.calls)}
        </span>
      </TD>
      <TD className={NUM}>{formatMs(usage.p50_ms)}</TD>
      <TD className={NUM}>{formatMs(usage.p95_ms)}</TD>
      <TD className={NUM}>{formatTokens(usage.input_tokens)}</TD>
      <TD className={NUM}>{formatTokens(usage.output_tokens)}</TD>
      <TD className={NUM}>
        <span title={unpriced > 0 ? `${unpriced} ${unpriced === 1 ? "call" : "calls"} unpriced` : undefined}>
          {formatCost(usage.cost_usd)}
        </span>
      </TD>
      <TD className={DATE}>{formatWhen(usage.first_call_in_range)}</TD>
      <TD className={DATE}>{formatWhen(usage.last_call_in_range)}</TD>
      {traces}
    </TR>
  );
}

/** The Metrics tab: calls, errors, latency, tokens and cost per version, inside one window. */
export function UsagePanel({ promptId, versions, links }: { promptId: string; versions: VersionSummary[]; links: UsageLinks }) {
  const [days, setDays] = useState("7");
  const usage = usePromptUsage(promptId, Number(days));

  const body = () => {
    if (usage.isPending) return <TableSkeleton rows={3} cols={12} />;
    if (usage.isError) return <ErrorNote error={usage.error} />;
    const { versions: rows, unknown_versions: unknown } = usage.data;
    if (rows.length === 0) {
      return (
        <EmptyState
          title={`No calls in the last ${days === "1" ? "day" : `${days} days`}`}
          body="A span counts here when it carries tessary.prompt.name. Playground runs are not recorded."
        />
      );
    }
    const byVersion = new Map(rows.map((u) => [u.version, u]));
    const known = new Set(versions.map((v) => String(v.version)));
    // The list page counts every span that names the prompt, so calls on no version or a version this prompt lacks
    // get rows here too.
    const other = rows.filter((u) => u.version === null || !known.has(u.version));
    return (
      <div className="flex flex-col gap-3" data-density="compact">
        <Table>
          <THead>
            <TR>
              {HEADERS.map((h, i) => (
                <TH key={h} className={isNumberColumn(i) ? "text-right" : undefined}>
                  {h}
                </TH>
              ))}
              <TH>
                <span className="sr-only">Traces</span>
              </TH>
            </TR>
          </THead>
          <TBody>
            {versions.map((v) => (
              <UsageRow
                key={v.id}
                row={{ label: formatVersionRef(String(v.version)), labels: v.labels, number: v.version }}
                usage={byVersion.get(String(v.version))}
                links={links}
              />
            ))}
            {other.map((u) => (
              <UsageRow
                key={u.version === null ? "none" : `sent:${u.version}`}
                row={{ label: u.version === null ? "No version" : formatVersionRef(u.version) }}
                usage={u}
                links={links}
              />
            ))}
          </TBody>
        </Table>
        {unknown.length > 0 && (
          <p className="text-small text-muted">{`Spans also name versions this prompt does not have: ${unknown.join(", ")}.`}</p>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-h2 text-fg">Metrics by version</h2>
        <SegmentedControl ariaLabel="Window" value={days} onChange={setDays} options={WINDOWS} />
      </div>
      {body()}
      <p className="text-small text-muted">
        A call counts when its span names this prompt and a version. The SDK adds both with prompt.link().{" "}
        <Link to={links.usePrompt} className={LINK}>
          View the code to use this prompt
        </Link>
      </p>
    </div>
  );
}
