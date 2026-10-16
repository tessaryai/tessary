// SPDX-License-Identifier: Apache-2.0
// The owning adapter writes every word the figure shows; this file only lays them out.
import type { ClassifierDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { RulingActions, detectorLabel, type FindingHeaderProps } from "./shared";
import { RateFigure } from "./frustrationStory";

export const FLAGGED_LIST_ID = "flagged-list";

export function ClassifierHeader(props: FindingHeaderProps) {
  const { finding, basePath } = props;
  return (
    <PageHeader
      breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
      kicker={<span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>}
      title={finding.title}
      subtitle={finding.callSiteId ? <span title={finding.causeKey}>{finding.callSiteId}</span> : undefined}
      actions={<RulingActions {...props} />}
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
