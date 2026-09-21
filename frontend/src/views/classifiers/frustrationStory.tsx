// SPDX-License-Identifier: Apache-2.0
/*
 * A frustration finding told as a rate of conversations: the share of this call site's conversations
 * frustrated with the agent since the onset, against the rate the call site learned as its normal, and
 * the conversations themselves, each beside the user turn that fired.
 *
 * <h2>Ruled at filing</h2>
 * A frustration finding never goes through triage: it is ruled positive when it is filed and opens its
 * case in the same step. So the header shows the ruling and never offers a triage button.
 *
 * <h2>Why the flagged turn's call site can differ</h2>
 * A conversation counts on the call site of its first scored turn, so numerator and denominator are the
 * same population. The turn that fired can sit on another call site; the list names it when it does,
 * so a reader looks for the cause where it happened.
 */
import { Link } from "react-router-dom";
import type { BehaviorFinding, FrustrationDetail } from "../../api/types";
import { PageHeader, Table, TBody, TD, TH, THead, TR, cn } from "../../ui";
import { detectorLabel, triageState } from "./shared";
import { Pin, PinList, RateChart, formatRate, perHundred } from "./rateStory";

const DAY_MONTH: Intl.DateTimeFormatOptions = { day: "numeric", month: "short" };

export function FrustrationHeader({
  detail,
  finding,
  basePath,
}: {
  detail: FrustrationDetail;
  finding: BehaviorFinding;
  basePath: string;
}) {
  const rate = detail.rate;
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleDateString(undefined, DAY_MONTH) : null;
  return (
    <PageHeader
      breadcrumb={[{ label: "Classifiers", to: `${basePath}/classifiers` }, { label: "Finding" }]}
      kicker={
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-muted">{finding.detector ? detectorLabel(finding.detector) : "Finding"}</span>
          {finding.callSiteId && <span className="text-muted">{finding.callSiteId}</span>}
        </span>
      }
      title={
        <span>
          Frustrated conversations{" "}
          <span className="font-mono font-medium text-subtle">{formatRate(rate.refRate)}</span>{" "}
          <span className="text-border-strong">→</span>{" "}
          <span className="font-mono font-medium text-error">{formatRate(rate.curRate)}</span>
        </span>
      }
      subtitle={
        since ? <span title={finding.causeKey}>since {since} vs the rate this call site learned</span> : undefined
      }
      actions={
        <span className="font-mono text-label uppercase text-muted rounded-control border border-border-strong bg-raised py-1.25 px-2.75">
          {triageState(finding).label}
        </span>
      }
    />
  );
}

/** "What moved": the rate chart tool error uses, and the two numbers behind it in conversations. */
export function FrustrationRate({ detail }: { detail: FrustrationDetail }) {
  const rate = detail.rate;
  const since = rate.onsetAt ? new Date(rate.onsetAt).toLocaleDateString(undefined, DAY_MONTH) : null;
  const points = Math.abs(rate.deltaPp);
  return (
    <>
      <RateChart rate={rate} label="Frustrated conversations" />
      <PinList>
        <Pin n={1} title={`${upper(perHundred(rate.curRate, "conversation"))} are frustrated now`}>
          {rate.failuresCur.toLocaleString()} of the {rate.nCur.toLocaleString()} conversations
          {since ? ` since ${since}` : ""} were frustrated with the agent. This is the number that opened the
          case.
        </Pin>
        <Pin n={2} title={`It was ${perHundred(rate.refRate, "conversation")}`}>
          This call site learned {formatRate(rate.refRate)} from its first {rate.nRef.toLocaleString()}{" "}
          conversations, {detail.baselineFrustrated.toLocaleString()} of them frustrated, so the rate is{" "}
          {points.toFixed(2)} percentage point{points === 1 ? "" : "s"} higher than its own normal. A user
          message counts as frustrated when its score is above {detail.jevThreshold.toFixed(2)}.
        </Pin>
      </PinList>
    </>
  );
}

function upper(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/**
 * The conversations the finding cites, newest first: a link to each conversation, the user turn that
 * fired in it, its score, and that turn's call site when it is not the finding's.
 */
export function FrustratedConversations({
  detail,
  callSiteId,
  basePath,
}: {
  detail: FrustrationDetail;
  callSiteId: string | null;
  basePath: string;
}) {
  const rows = detail.conversations;
  if (rows.length === 0) {
    return (
      <p className="text-subtle mt-2 text-body" style={{ maxWidth: 560 }}>
        No frustrated conversations are stored for this finding. Their traces may have aged out.
      </p>
    );
  }
  const otherCallSite = rows.some((r) => r.callSiteId && r.callSiteId !== callSiteId);
  return (
    <div className="mt-2 flex flex-col gap-2">
      <Table className="text-body" style={{ minWidth: 640 }}>
        <THead>
          <TR>
            <TH>Conversation</TH>
            <TH>Flagged turn</TH>
            <TH className="text-right">Score</TH>
            {otherCallSite && <TH>Turn's call site</TH>}
            <TH>Flagged at</TH>
          </TR>
        </THead>
        <TBody>
          {rows.map((r) => (
            <TR key={r.traceId}>
              <TD className="px-3 py-1.75" style={{ whiteSpace: "nowrap" }}>
                <Link
                  to={`${basePath}/sessions/${encodeURIComponent(r.conversationId)}`}
                  className="font-mono text-link hover:text-link-hover transition-colors"
                >
                  {short(r.conversationId)}
                </Link>
                {r.cleared && <span className="text-subtle text-small ml-2">cleared</span>}
              </TD>
              <TD className="px-3 py-1.75" style={{ whiteSpace: "nowrap" }}>
                <Link
                  to={`${basePath}/traces/${encodeURIComponent(r.traceId)}`}
                  className="font-mono text-link hover:text-link-hover transition-colors"
                >
                  {short(r.traceId)}
                </Link>
              </TD>
              <TD className={cn("px-3 py-1.75 text-right tabular-nums", r.cleared ? "text-muted" : "text-error")}>
                {r.score != null ? r.score.toFixed(2) : "—"}
              </TD>
              {otherCallSite && (
                <TD className="px-3 py-1.75 font-mono text-muted" style={{ whiteSpace: "nowrap" }}>
                  {r.callSiteId && r.callSiteId !== callSiteId ? r.callSiteId : ""}
                </TD>
              )}
              <TD className="px-3 py-1.75" style={{ whiteSpace: "nowrap" }}>
                {r.flaggedAt ? new Date(r.flaggedAt).toLocaleString() : "—"}
              </TD>
            </TR>
          ))}
        </TBody>
      </Table>
      <p className="text-subtle m-0 text-small">
        {rows.length.toLocaleString()} of {detail.rate.failuresCur.toLocaleString()} frustrated conversations,
        newest first. A conversation counts on the call site of its first scored turn.
      </p>
    </div>
  );
}

function short(id: string): string {
  return id.length > 12 ? `${id.slice(0, 12)}…` : id;
}
