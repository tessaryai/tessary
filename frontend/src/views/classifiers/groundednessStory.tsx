// SPDX-License-Identifier: Apache-2.0
// The model's flags include false alarms and miss real ones, so only the rate's direction is reliable:
// every number is labelled a flagged share, never a share of wrong answers.
import type { GroundednessDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { RulingActions, detectorLabel, type FindingHeaderProps } from "./shared";
import { Pin, PinList, RateChart } from "./rateStory";
import { dateTime } from "./groundedness";

/** One decimal: the flagged share is a direction, not a measurement. */
function pinRate(r: number): string {
  return `${(r * 100).toFixed(1)}%`;
}

export function GroundednessHeader({ detail, ...props }: FindingHeaderProps & { detail: GroundednessDetail }) {
  const { finding, basePath } = props;
  const rate = detail.rate;
  const since = rate.onsetAt ? dateTime(rate.onsetAt) : null;
  const learned = `vs the rate it learned from its first ${detail.baselineTraces.toLocaleString()} traces`;
  return (
    <PageHeader
      breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
      kicker={<span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Groundedness"}</span>}
      title={finding.title}
      subtitle={
        <span title={finding.causeKey}>
          {since ? `since ${since} ${learned}` : learned}
        </span>
      }
      actions={<RulingActions {...props} />}
    />
  );
}

export function GroundednessRate({
  detail,
  opened = "the finding",
}: {
  detail: GroundednessDetail;
  opened?: "the finding" | "this case";
}) {
  const rate = detail.rate;
  const since = rate.onsetAt ? dateTime(rate.onsetAt) : null;
  const points = Math.abs(rate.deltaPp);
  return (
    <>
      <RateChart rate={rate} label="Flagged answers" />
      <PinList>
        <Pin n={1} title={`${pinRate(rate.curRate)} of answers flagged`}>
          {rate.failuresCur.toLocaleString()} of {rate.nCur.toLocaleString()} traces
          {since ? ` since ${since}` : ""} had an answer flagged as unsupported. This rate opened {opened}.
        </Pin>
        <Pin n={2} title={`${pinRate(rate.refRate)} is normal for this call site`}>
          Learned from its first {detail.baselineTraces.toLocaleString()} traces. The current rate is{" "}
          {points.toFixed(1)} percentage {points === 1 ? "point" : "points"} higher. An answer is flagged when
          one of its sentences scores {detail.flagThreshold} or higher.
        </Pin>
      </PinList>
    </>
  );
}
