// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import type { VersionSummary, VersionUsage } from "../../api/types";
import { EmptyState, ErrorNote, SegmentedControl, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { formatCost, formatTokens, formatWhen } from "../traces/index-data";
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

const NUM = "whitespace-nowrap text-right font-mono tabular-nums";
const DATE = "whitespace-nowrap font-mono text-small text-muted";

function UsageRow({ label, usage }: { label: string; usage: VersionUsage | undefined }) {
  if (!usage) {
    return (
      <TR>
        <TD className="font-mono">{label}</TD>
        <TD className={NUM}>0</TD>
        {Array.from({ length: 8 }, (_, i) => (
          <TD key={i} className={NUM}>
            —
          </TD>
        ))}
      </TR>
    );
  }
  const unpriced = usage.unpriced_calls;
  return (
    <TR>
      <TD className="font-mono">{label}</TD>
      <TD className={NUM}>{usage.calls.toLocaleString("en-US")}</TD>
      <TD className={NUM}>{usage.error_calls.toLocaleString("en-US")}</TD>
      <TD className={NUM}>{formatMs(usage.p50_ms)}</TD>
      <TD className={NUM}>{formatMs(usage.p95_ms)}</TD>
      <TD className={NUM}>{formatTokens(usage.input_tokens)}</TD>
      <TD className={NUM}>{formatTokens(usage.output_tokens)}</TD>
      <TD className={NUM}>
        <span title={unpriced > 0 ? `${unpriced} ${unpriced === 1 ? "call" : "calls"} unpriced` : undefined}>
          {formatCost(usage.cost_usd)}
        </span>
      </TD>
      <TD className={DATE}>{formatWhen(usage.first_seen)}</TD>
      <TD className={DATE}>{formatWhen(usage.last_seen)}</TD>
    </TR>
  );
}

export function UsagePanel({ promptId, versions }: { promptId: string; versions: VersionSummary[] }) {
  const [days, setDays] = useState("7");
  const usage = usePromptUsage(promptId, Number(days));

  const body = () => {
    if (usage.isPending) return <TableSkeleton rows={3} cols={10} />;
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
              {["Version", "Calls", "Errors", "p50", "p95", "Input tokens", "Output tokens", "Cost", "First seen", "Last seen"].map(
                (h, i) => (
                  <TH key={h} className={i > 0 && i < 8 ? "text-right" : undefined}>
                    {h}
                  </TH>
                ),
              )}
            </TR>
          </THead>
          <TBody>
            {versions.map((v) => (
              <UsageRow key={v.id} label={formatVersionRef(String(v.version))} usage={byVersion.get(String(v.version))} />
            ))}
            {other.map((u) => (
              <UsageRow
                key={u.version === null ? "none" : `sent:${u.version}`}
                label={u.version === null ? "No version" : formatVersionRef(u.version)}
                usage={u}
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
        <h3 className="text-h3 text-fg">Usage by version</h3>
        <SegmentedControl ariaLabel="Window" value={days} onChange={setDays} options={WINDOWS} />
      </div>
      {body()}
    </div>
  );
}
