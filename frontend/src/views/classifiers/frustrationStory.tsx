// SPDX-License-Identifier: Apache-2.0
/*
 * A frustration finding told as a rate of conversations: the share of this call site's conversations
 * frustrated with the agent since the onset, against the rate the call site learned as its normal. The
 * conversations themselves are {@link FrustratedConversations}, shared with the case page.
 *
 * <h2>Ruled at filing</h2>
 * A frustration finding never goes through triage: it is ruled positive when it is filed and opens its
 * case in the same step. So the header shows the ruling and the case it opened, never a triage button.
 */
import { Link } from "react-router-dom";
import type { BehaviorFinding, FrustrationDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { triageState } from "./shared";
import { Pin, PinList, RateChart, formatRate } from "./rateStory";

const DAY_MONTH_TIME: Intl.DateTimeFormatOptions = {
  day: "numeric",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
};

export function FrustrationHeader({
  finding,
  basePath,
}: {
  finding: BehaviorFinding;
  basePath: string;
}) {
  const closed = finding.status === "closed";
  const caseRef = finding.caseId;
  return (
    <PageHeader
      breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
      title={finding.title}
      subtitle={
        closed
          ? "This finding is closed. This call site is learning its normal rate again from its next sessions."
          : "Frustration findings skip triage. Tessary opened a case as soon as the rate rose above this call site's normal."
      }
      actions={
        <span className="flex items-center gap-2">
          <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
            {triageState(finding).label}
          </span>
          {caseRef && (
            <Link
              to={`${basePath}/cases/${encodeURIComponent(caseRef)}`}
              className="inline-flex items-center h-7 px-2.5 rounded-control border border-border-strong bg-raised text-small text-fg hover:bg-hover transition-colors"
              style={{ transitionDuration: "var(--duration-micro)" }}
            >
              View case
            </Link>
          )}
        </span>
      }
    />
  );
}

/** "What changed": the rate chart tool error uses, and the two numbers behind it in sessions. */
export function FrustrationRate({ detail }: { detail: FrustrationDetail }) {
  const rate = detail.rate;
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleString(undefined, DAY_MONTH_TIME) : null;
  const points = Math.abs(rate.deltaPp);
  return (
    <>
      <RateChart rate={rate} label="Frustrated sessions" />
      <PinList>
        <Pin n={1} title={`${formatRate(rate.curRate)} of sessions are frustrated`}>
          {rate.failuresCur.toLocaleString()} of {rate.nCur.toLocaleString()} sessions
          {since ? ` since ${since}` : ""} had a user frustrated with the agent. This rate opened the case.
        </Pin>
        <Pin n={2} title={`${formatRate(rate.refRate)} is normal for this call site`}>
          Learned from its first {rate.nRef.toLocaleString()} sessions. The current rate is{" "}
          {points.toFixed(1)} percentage {points === 1 ? "point" : "points"} higher. A message counts as
          frustrated when its score is above {detail.jevThreshold.toFixed(2)}.
        </Pin>
      </PinList>
    </>
  );
}
