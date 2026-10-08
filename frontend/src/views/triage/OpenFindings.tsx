// SPDX-License-Identifier: Apache-2.0
/*
 * Triage's Findings section: every open finding that has not become a case yet.
 *
 * A finding is in one of two places on this page, never both. Triage turns a sound finding into a case, and from
 * then on the case is the thing to work, so a `positive` finding that carries a case lives in Cases above. What
 * is left here is everything still on its way: pending, in flight, a run that gave up, and a sound finding whose
 * case has not been written yet. A finding triage closed is done and shows nowhere.
 *
 * The title is the classifier's own sentence. Each detector writes its finding's title with its own numbers in it
 * ("search_docs is failing 3.1% of the time, up from 0.4%"), so there is no shared "reading" column restating the
 * shift: the detectors do not measure comparable things, and one column would fit none of them.
 */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router-dom";
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

  // `include: "all"` by default: the gated read returns only what triage found sound, which hides every finding
  // still waiting for its run.
  const findingsQ = useQuery({
    queryKey: ["behavior-findings", api.base, "all"],
    queryFn: () => api.listBehaviorFindings(),
  });
  const open = useMemo(() => (findingsQ.data?.findings ?? []).filter(awaitsCase), [findingsQ.data]);
  const openFinding = (id: string) => navigate(`${basePath}/classifiers/findings/${encodeURIComponent(id)}`);

  return (
    <Section title="Findings" count={findingsQ.data && open.length > 0 ? open.length : undefined}>
      {findingsQ.isLoading && <TableSkeleton rows={3} cols={5} />}
      {findingsQ.isError && <ErrorNote error={findingsQ.error} />}
      {findingsQ.data && open.length === 0 && (
        <div className="bg-surface border border-border rounded-card text-subtle py-3.5 px-4 text-small">
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
              <TR key={f.id} interactive onClick={() => openFinding(f.id)}>
                <TD className="text-fg truncate" style={{ maxWidth: 0 }} title={f.title}>
                  {f.title}
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

/**
 * Where triage left the finding. A run that gave up gets a red icon beside grey words: red text fails contrast at
 * table size, and the icon alone is enough to pull the eye.
 */
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
