// SPDX-License-Identifier: Apache-2.0
// Every open finding that has not become a case yet: a finding is in Cases or here, never both. The title
// is the classifier's own sentence, since detectors do not measure comparable things.
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { AlertCircle, ChevronRight } from "lucide-react";
import type { BehaviorFinding } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import { ErrorNote, Section, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { ago, detectorLabel, isClosedByTriage, triageState } from "../classifiers/shared";
import { displayCallSite } from "./bits";

/** Open, and not yet the case Cases lists. */
function awaitsCase(finding: BehaviorFinding): boolean {
  if (isClosedByTriage(finding)) return false;
  return !(finding.triageVerdict === "positive" && finding.caseId != null);
}

export function OpenFindings({ basePath }: { basePath: string }) {
  const { api } = useTenant();
  const navigate = useNavigate();

  // `include: "all"`: the gated read returns only what triage found sound, hiding findings awaiting their run.
  const findingsQ = useQuery({
    queryKey: ["behavior-findings", api.base, "all"],
    queryFn: () => api.listBehaviorFindings(),
  });
  const open = useMemo(() => (findingsQ.data?.findings ?? []).filter(awaitsCase), [findingsQ.data]);
  const findingPath = (id: string) => `${basePath}/classifiers/findings/${encodeURIComponent(id)}`;

  return (
    <Section title="Findings" count={findingsQ.data && open.length > 0 ? open.length : undefined}>
      {findingsQ.isLoading && <TableSkeleton rows={3} cols={5} />}
      {findingsQ.isError && <ErrorNote error={findingsQ.error} />}
      {findingsQ.data && open.length === 0 && (
        <div className="bg-surface border border-border rounded-card text-muted py-3.5 px-4 text-small">
          No open findings.
        </div>
      )}
      {open.length > 0 && (
        <Table>
          <THead>
            <TR>
              <TH>Finding</TH>
              <TH style={{ width: 150 }}>Classifier</TH>
              <TH style={{ width: 170 }}>Call site</TH>
              <TH style={{ width: 150 }}>Triage</TH>
              <TH style={{ width: 110 }}>First seen</TH>
              <TH style={{ width: 32 }} />
            </TR>
          </THead>
          <TBody>
            {open.map((f) => (
              // The row opens the finding for a mouse; the title link is for keyboard and screen readers and stops its click.
              <TR key={f.id} interactive onClick={() => navigate(findingPath(f.id))}>
                <TD className="truncate" style={{ maxWidth: 0 }} title={f.title}>
                  <Link
                    to={findingPath(f.id)}
                    onClick={(e) => e.stopPropagation()}
                    className="text-accent hover:text-accent-hover transition-colors"
                    style={{ transitionDuration: "var(--duration-micro)" }}
                  >
                    {f.title}
                  </Link>
                </TD>
                <TD className="text-muted truncate">{f.detector ? detectorLabel(f.detector) : "–"}</TD>
                <TD className="font-mono text-muted truncate" style={{ maxWidth: 0 }}>
                  {displayCallSite(f.callSiteId) ?? "–"}
                </TD>
                <TD>
                  <TriageCell finding={f} />
                </TD>
                <TD className="text-subtle whitespace-nowrap" title={new Date(f.firstSeenAt).toLocaleString()}>
                  {ago(f.firstSeenAt)}
                </TD>
                <TD className="text-subtle">
                  <ChevronRight size={12} strokeWidth={1.75} aria-hidden="true" />
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      )}
    </Section>
  );
}

/** Where triage left the finding. A run that gave up gets a red icon beside grey words; red text fails contrast. */
function TriageCell({ finding }: { finding: BehaviorFinding }) {
  const state = triageState(finding);
  if (state.tone === "failed") {
    return (
      <span className="flex items-center gap-1.5 whitespace-nowrap">
        <AlertCircle size={13} strokeWidth={1.75} className="text-error shrink-0" aria-hidden="true" />
        <span className="text-fg-secondary">{state.label}</span>
      </span>
    );
  }
  return (
    <span className={`${state.tone === "positive" ? "text-fg" : "text-muted"} whitespace-nowrap`}>{state.label}</span>
  );
}
