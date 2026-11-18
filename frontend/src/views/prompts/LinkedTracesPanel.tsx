// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import { ChevronRight } from "lucide-react";
import { useTenant } from "../../tenant/TenantContext";
import { EmptyState, ErrorNote, StatusPill, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { traceLinker } from "../traceLinker";
import { formatCost, formatLatency, formatWhen } from "../traces/index-data";
import { usePromptTraces } from "./promptReads";

const NUM = "whitespace-nowrap text-right font-mono tabular-nums";

export function LinkedTracesPanel({ name, version }: { name: string; version: number }) {
  const { orgSlug, projectSlug } = useTenant();
  const basePath = `/orgs/${orgSlug}/projects/${projectSlug}`;
  const traceHref = traceLinker(basePath);
  const traces = usePromptTraces(name, version);

  const body = () => {
    if (traces.isPending) return <TableSkeleton rows={4} cols={5} />;
    if (traces.isError) return <ErrorNote error={traces.error} />;
    if (traces.data.traces.length === 0) {
      return (
        <EmptyState
          title={`No traces for version ${version} yet`}
          body="A trace appears here when one of its spans carries this prompt's name and version."
        />
      );
    }
    return (
      <div className="flex flex-col gap-3">
        <Table>
          <THead>
            <TR>
              <TH>When</TH>
              <TH>Trace</TH>
              <TH>Status</TH>
              <TH className="text-right">Latency</TH>
              <TH className="text-right">Cost</TH>
            </TR>
          </THead>
          <TBody>
            {traces.data.traces.map((t) => (
              <TR key={t.id}>
                <TD className="whitespace-nowrap font-mono text-small text-muted">{formatWhen(t.started_at)}</TD>
                <TD>
                  <Link to={traceHref(t.id)} className="text-link hover:text-link-hover">
                    {t.name ?? t.id}
                  </Link>
                  <div className="font-mono text-small text-muted">{t.id}</div>
                </TD>
                <TD>
                  {t.status === "error" ? <StatusPill status="failed" label="error" /> : <StatusPill status="passed" label="ok" />}
                </TD>
                <TD className={NUM}>{formatLatency(t.latency_ms)}</TD>
                <TD className={NUM}>{formatCost(t.total_cost)}</TD>
              </TR>
            ))}
          </TBody>
        </Table>
        {traces.data.next_cursor && (
          <p className="text-small text-muted">Showing the first 50 traces. Open in Traces to see every one.</p>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-3">
        <h3 className="text-h3 text-fg">Traces for version {version}</h3>
        <Link
          to={`${basePath}/traces?prompt=${encodeURIComponent(name)}&prompt_version=${version}`}
          className="inline-flex items-center gap-1 text-small text-link hover:text-link-hover"
        >
          Open in Traces
          <ChevronRight size={12} strokeWidth={1.75} aria-hidden="true" />
        </Link>
      </div>
      {body()}
    </div>
  );
}
