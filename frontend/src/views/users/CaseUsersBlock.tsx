// SPDX-License-Identifier: Apache-2.0
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type { EndUserBanner, EndUserCaseBlock, EndUserIssueNumbers } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { Badge, Card, ErrorNote, LoadingRow } from "../../ui";
import { RetentionChart } from "./chart/RetentionChart";
import { endUserQueries } from "./usersQueries";
import {
  ARROW,
  caseCountLine,
  issueNoteUnder,
  issueRowLabel,
  lossText,
  NOT_APPLICABLE,
  SEE_USERS_LOST,
  verdictWord,
} from "./usersWords";

/** Board P4: the user IDs a case hit and its verdict, read on its own so a failed read stays inside the block. The overview's banners decide the note's caveat. */
export function CaseUsersBlock({ caseId }: { caseId: string }) {
  const api = useProjectApi();
  const q = useQuery(endUserQueries.caseBlock(api, caseId));
  const overview = useQuery(endUserQueries.overview(api));
  if (q.isError) return <ErrorNote error={q.error} />;
  if (overview.isError) return <ErrorNote error={overview.error} />;
  if (q.isPending || overview.isPending) return <LoadingRow />;
  const block = q.data;
  const issue = block.issue;
  return (
    <Card className="border border-border grid gap-5 p-5 md:grid-cols-[minmax(0,1fr)_minmax(0,24rem)] md:items-center">
      <div className="flex flex-col gap-3">
        {block.count_kind === "not_applicable" ? (
          <p className="m-0 text-body text-fg">{NOT_APPLICABLE}</p>
        ) : (
          <CountLine {...caseCountLine(block)} />
        )}
        {issue && <Verdict block={block} issue={issue} banners={overview.data.banners} />}
      </div>
      {issue?.curve && <RetentionChart variant="block" issue={{ ...issue, curve: issue.curve }} />}
    </Card>
  );
}

function CountLine({ lead, rest }: { lead: string; rest: string }) {
  return (
    <p className="m-0 text-body text-fg tabular-nums">
      <strong className="font-medium">{lead}</strong>
      {rest}
    </p>
  );
}

/** A shown issue states its loss and its stops and links to its users; any other verdict says why it has no number. */
function Verdict({
  block,
  issue,
  banners,
}: {
  block: EndUserCaseBlock;
  issue: EndUserIssueNumbers;
  banners: EndUserBanner[];
}) {
  return (
    <>
      <p className="m-0 flex flex-wrap items-center gap-2.5 text-body text-fg tabular-nums">
        <Badge tone={issue.verdict === "evidence_of_loss" ? "error" : "neutral"}>{verdictWord(issue.verdict)}</Badge>
        <span>{issue.shown ? lossText(issue) : issueRowLabel(issue)}</span>
      </p>
      {issue.shown && (
        <>
          <p className="m-0 text-small text-fg-secondary">{issueNoteUnder(issue, banners)}</p>
          <Link
            to={`../users/issues/${encodeURIComponent(block.case_ref)}`}
            className="self-start text-small text-link no-underline hover:text-link-hover"
          >
            {`${SEE_USERS_LOST} ${ARROW}`}
          </Link>
        </>
      )}
    </>
  );
}
