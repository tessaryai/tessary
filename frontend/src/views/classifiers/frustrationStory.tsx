// SPDX-License-Identifier: Apache-2.0
/*
 * A frustration finding told as a rate of conversations: the share of this call site's conversations
 * frustrated with the agent since the onset, against the rate the call site learned as its normal. The
 * conversations themselves are a {@link FlaggedList}, shared with the case page, fed by
 * {@link frustrationRows} and {@link frustrationLoader}.
 *
 * <h2>Ruled at filing</h2>
 * A frustration finding never goes through triage: it is ruled positive when it is filed and opens its
 * case in the same step. So the header shows the ruling and the case it opened, never a triage button.
 */
import { Link } from "react-router-dom";
import type { ProjectApi } from "../../api/client";
import type { BehaviorFinding, FrustratedConversation, FrustrationDetail } from "../../api/types";
import { PageHeader } from "../../ui";
import { triageState } from "./shared";
import { Pin, PinList, RateChart, formatRate } from "./rateStory";
import type { FlaggedLoader, FlaggedPage, FlaggedRow } from "./FlaggedList";

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
      breadcrumb={[{ label: "Triage", to: `${basePath}/triage` }, { label: "Finding" }]}
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

/**
 * "What changed" for a rate of flagged trials: the rate chart tool error uses, then two pins. Pin 1 is the
 * current rate in the caller's noun; pin 2 is the reference rate, with the caller's reference words, how far
 * the current rate sits from it, and the caller's line on when a trial counts. Every word that differs by
 * classifier comes from the caller.
 */
export function RateFigure({
  rate,
  label,
  unit,
  flagged,
  flaggedLine,
  referenceLabel,
  referenceDetail,
  thresholdLine,
}: {
  rate: FrustrationDetail["rate"];
  /** The chart's name. */
  label: string;
  /** What the rate counts, plural: "sessions". */
  unit: string;
  /** Pin 1's claim after "N% of <unit>": "are frustrated". */
  flagged: string;
  /** Pin 1's line after "F of N <unit> since <onset>": "had a user frustrated with the agent." */
  flaggedLine: string;
  /** Pin 2's claim after the reference rate: "is normal for this call site". */
  referenceLabel: string;
  /** Pin 2's line before the distance from the reference, or null. */
  referenceDetail: string | null;
  /** Pin 2's line after it, on when a trial counts as flagged, or null. */
  thresholdLine: string | null;
}) {
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleString(undefined, DAY_MONTH_TIME) : null;
  const points = Math.abs(rate.deltaPp);
  const distance = `The current rate is ${points.toFixed(1)} percentage ${points === 1 ? "point" : "points"} ${
    rate.deltaPp < 0 ? "lower" : "higher"
  }.`;
  return (
    <>
      <RateChart rate={rate} label={label} />
      <PinList>
        <Pin n={1} title={`${formatRate(rate.curRate)} of ${unit} ${flagged}`}>
          {`${rate.failuresCur.toLocaleString()} of ${rate.nCur.toLocaleString()} ${unit}${since ? ` since ${since}` : ""} ${flaggedLine}`}
        </Pin>
        <Pin n={2} title={`${formatRate(rate.refRate)} ${referenceLabel}`}>
          {[referenceDetail, distance, thresholdLine].filter(Boolean).join(" ")}
        </Pin>
      </PinList>
    </>
  );
}

/** "What changed": the rate figure in sessions, with frustration's words. */
export function FrustrationRate({ detail }: { detail: FrustrationDetail }) {
  return (
    <RateFigure
      rate={detail.rate}
      label="Frustrated sessions"
      unit="sessions"
      flagged="are frustrated"
      flaggedLine="had a user frustrated with the agent. This rate opened the case."
      referenceLabel="is normal for this call site"
      referenceDetail={`Learned from its first ${detail.rate.nRef.toLocaleString()} sessions.`}
      thresholdLine={`A message counts as frustrated when its score is above ${detail.jevThreshold.toFixed(2)}.`}
    />
  );
}

/** Sessions read per page past the first. */
const PAGE_SIZE = 50;

/** A frustrated session as a flagged row: the flagged trace's question is the part the classifier judged. */
function frustrationRow(c: FrustratedConversation): FlaggedRow {
  return {
    id: c.conversationId,
    traceId: c.traceId,
    score: c.score,
    flaggedAt: c.flaggedAt,
    cleared: c.cleared,
    sessionId: c.sessionId,
    contextTraceIds: c.contextTraceIds,
    preview: c.message,
    emphasis: [{ traceId: c.traceId, spanId: null, part: "question", kind: "judged", label: "Frustration" }],
  };
}

/** The frustrated sessions a finding came with, as the list's first page. */
export function frustrationRows(detail: FrustrationDetail): FlaggedPage {
  return {
    rows: detail.conversations.map(frustrationRow),
    nextCursor: detail.conversationsNextCursor,
    total: detail.rate.failuresCur,
  };
}

/** Reads a finding's frustrated sessions a page at a time, the whole list or one cause's. */
export function frustrationLoader(api: ProjectApi, findingId: string): FlaggedLoader {
  return async (filter, cursor) => {
    const page = await api.getFrustratedSessions(findingId, { limit: PAGE_SIZE, cursor, cause: filter });
    return { rows: page.rows.map(frustrationRow), nextCursor: page.nextCursor, total: page.total };
  };
}

/**
 * The words frustration's list uses. Its pane draws only the flagged message's mark and dims nothing, so
 * it reads as a conversation rather than as one part judged among others.
 */
export const FRUSTRATION_LIST_COPY: {
  listLabel: string;
  unit: [string, string];
  emptyText: string;
  missingText: string;
  dim: boolean;
} = {
  listLabel: "Frustrated sessions",
  unit: ["session", "sessions"],
  emptyText: "No frustrated sessions are stored for this finding. Their traces may have aged out.",
  missingText: "This session's traces are no longer stored, so its messages can't be shown.",
  dim: false,
};
