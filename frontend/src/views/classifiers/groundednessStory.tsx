// SPDX-License-Identifier: Apache-2.0
/*
 * A groundedness finding told as a rate of traces: the share of this call site's traces with an answer
 * flagged as unsupported since the onset, against the rate the call site learned as its normal. The
 * answers themselves are {@link FlaggedAnswers}, shared with the case page.
 *
 * <h2>Flagged, not wrong</h2>
 * The model's flags include false alarms and miss real ones, so the direction of the rate is reliable
 * and its size is not. The title says only the direction; every number on the page is labelled as a
 * flagged share, never as a share of wrong answers.
 *
 * <h2>Triaged like a rate finding</h2>
 * Unlike frustration, a groundedness finding goes through triage, so before a ruling the header offers
 * the triage run and the two resolve verbs, and after one it shows the ruling and the case it opened.
 */
import { Link } from "react-router-dom";
import type { BehaviorFinding, GroundednessDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { ResolveVerbs, RunTriageButton, detectorLabel, triageState } from "./shared";
import { Pin, PinList, RateChart } from "./rateStory";
import { dateTime } from "./groundedness";

/** `0.0642` → `6.4%`. One decimal in the pins: the flagged share is a direction, not a measurement. */
function pinRate(r: number): string {
  return `${(r * 100).toFixed(1)}%`;
}

export function GroundednessHeader({
  finding,
  detail,
  basePath,
  busy,
  onAnalyze,
  onResolve,
}: {
  finding: BehaviorFinding;
  detail: GroundednessDetail;
  basePath: string;
  busy: boolean;
  onAnalyze: () => void;
  onResolve: (action: "expected" | "not_expected") => void;
}) {
  const rate = detail.rate;
  const since = rate.onsetAt ? dateTime(rate.onsetAt) : null;
  const learned = `vs the rate it learned from its first ${detail.baselineTraces.toLocaleString()} traces`;
  return (
    <PageHeader
      breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
      kicker={<span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Groundedness"}</span>}
      title={finding.title}
      subtitle={
        <span title={finding.causeKey}>
          {since ? `since ${since} ${learned}` : learned}
        </span>
      }
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

/**
 * "What changed": the rate chart tool error uses, and the two numbers behind it in traces. `opened` names
 * what the rate opened: the finding on its own page, the case on the case page.
 */
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
