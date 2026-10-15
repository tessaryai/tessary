// SPDX-License-Identifier: Apache-2.0
// The owning adapter writes every word the figure shows; this file only lays them out.
import { Link } from "react-router-dom";
import type { BehaviorFinding, ClassifierDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { ResolveVerbs, RunTriageButton, detectorLabel, triageState } from "./shared";
import { RateFigure } from "./frustrationStory";

export const FLAGGED_LIST_ID = "flagged-list";

export function ClassifierHeader({
  finding,
  basePath,
  busy,
  onAnalyze,
  onResolve,
}: {
  finding: BehaviorFinding;
  basePath: string;
  busy: boolean;
  onAnalyze: () => void;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  return (
    <PageHeader
      breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
      kicker={<span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>}
      title={finding.title}
      subtitle={finding.callSiteId ? <span title={finding.causeKey}>{finding.callSiteId}</span> : undefined}
      actions={
        finding.triageStatus === "done" ? (
          <span className="flex items-center gap-2">
            <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
              {triageState(finding).label}
            </span>
            {finding.caseId && (
              <Link
                to={`${basePath}/cases/${encodeURIComponent(finding.caseId)}`}
                className="inline-flex items-center h-7 px-2.5 rounded-control border border-border-strong bg-raised text-small text-fg hover:bg-hover transition-colors"
                style={{ transitionDuration: "var(--duration-micro)" }}
              >
                View case
              </Link>
            )}
          </span>
        ) : (
          <span className="flex flex-wrap items-center gap-2">
            <ResolveVerbs busy={busy} onResolve={onResolve} />
            <RunTriageButton finding={finding} busy={busy} onAnalyze={onAnalyze} />
          </span>
        )
      }
    />
  );
}

export function ClassifierRate({ detail }: { detail: ClassifierDetail }) {
  const words = detail.words;
  return (
    <RateFigure
      rate={detail.rate}
      label={`Flagged ${words.unitMany}`}
      unit={words.unitMany}
      flagged="are flagged"
      flaggedLine="were flagged."
      referenceLabel={words.referenceLabel}
      referenceDetail={words.referenceDetail}
      thresholdLine={words.thresholdLine}
    />
  );
}
