// SPDX-License-Identifier: Apache-2.0
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams, useSearchParams } from "react-router-dom";
import type { EndUserBanner, EndUserIssue, EndUserIssueNumbers, EndUserVerdict } from "../../api/types";
import { searchPatch } from "../../lib/searchPatch";
import { useProjectApi } from "../../tenant/TenantContext";
import { Badge, Card, ErrorNote, LoadingRow, PageBody, PageHeader } from "../../ui";
import { RetentionChart } from "./chart/RetentionChart";
import { SilentIdsRail } from "./SilentIdsRail";
import { endUserQueries } from "./usersQueries";
import {
  ARROW,
  hitChip,
  HOW_COUNTED_HIDE,
  HOW_COUNTED_SHOW,
  ISSUE_METHOD,
  issueHeadline,
  issueHowCounted,
  issueNoteUnder,
  NOT_PROOF,
  openCase,
  PAGE_TITLE,
  silentNowCount,
  verdictWord,
} from "./usersWords";

/** Spec level 2: only these verdicts list their hit user IDs; any other list would print a comparison with no claim. */
const CLAIMS: ReadonlySet<EndUserVerdict> = new Set(["evidence_of_loss", "few_accounts"]);

/** The drawer button and the "Open case" link look like a secondary button. */
const CONTROL = "inline-flex h-9 items-center rounded-control border border-border-strong bg-raised px-3.5 text-small text-fg";

function HowCounted({ issue, sinceDay }: { issue: EndUserIssueNumbers; sinceDay: number | null }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen(!open)}
        className="ml-auto cursor-pointer bg-transparent text-small text-link hover:text-link-hover"
      >
        {open ? HOW_COUNTED_HIDE : HOW_COUNTED_SHOW}
      </button>
      {open && (
        <Card
          id={panelId}
          role="region"
          aria-label={HOW_COUNTED_SHOW}
          className="basis-full flex flex-col gap-1.5 border border-border px-4.5 py-3.5 text-small text-fg-secondary"
        >
          <span>{issueHowCounted(issue, sinceDay)}</span>
          {ISSUE_METHOD.map((line) => (
            <span key={line}>{line}</span>
          ))}
        </Card>
      )}
    </>
  );
}

function IssueBody({ data, issue, banners }: { data: EndUserIssue; issue: EndUserIssueNumbers; banners: EndUserBanner[] }) {
  const [params, setParams] = useSearchParams();
  const patch = searchPatch(params, setParams);
  const claims = CLAIMS.has(issue.verdict);
  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1.5">
        <h2 className="m-0 text-h2 text-fg">{issue.shown ? issueHeadline(issue) : hitChip(issue.user_ids_hit)}</h2>
        <p className="m-0 text-body text-fg-secondary">{issueNoteUnder(issue, banners)}</p>
      </div>
      {issue.curve && <RetentionChart variant="page" issue={{ ...issue, curve: issue.curve }} />}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
        {claims && (
          <button
            type="button"
            onClick={() => patch({ silent: "1" })}
            className={`${CONTROL} hover:bg-overlay`}
          >
            {`${silentNowCount(data.silent_now!)} ${ARROW}`}
          </button>
        )}
        <span className="text-small text-muted">{NOT_PROOF}</span>
        {issue.compared != null && <HowCounted issue={issue} sinceDay={data.since_day} />}
      </div>
      {claims && params.get("silent") === "1" && (
        <SilentIdsRail
          caseRef={data.case_ref}
          verdict={issue.verdict}
          silentNow={data.silent_now!}
          onClose={() => patch({ silent: null })}
        />
      )}
    </div>
  );
}

function Issue({ data, banners }: { data: EndUserIssue; banners: EndUserBanner[] }) {
  const issue = data.issue;
  return (
    <>
      <PageHeader
        breadcrumb={[{ label: PAGE_TITLE, to: "../users" }, { label: data.case_ref }]}
        title={data.title}
        titleAside={
          issue && (
            <Badge tone={issue.verdict === "evidence_of_loss" ? "error" : "neutral"}>{verdictWord(issue.verdict)}</Badge>
          )
        }
        actions={
          <Link
            to={`../cases/${encodeURIComponent(data.case_id)}`}
            className={`${CONTROL} no-underline hover:bg-overlay`}
          >
            {openCase(data.case_ref)}
          </Link>
        }
      />
      {issue && <IssueBody data={data} issue={issue} banners={banners} />}
    </>
  );
}

/** Board P2: one issue's loss, its curve, and the drawer of board P3 at `?silent=1`. The overview's banners decide the note's caveat. */
export function IssuePage() {
  const api = useProjectApi();
  const { caseId } = useParams<{ caseId: string }>();
  const q = useQuery(endUserQueries.issue(api, caseId!));
  const overview = useQuery(endUserQueries.overview(api));
  return (
    <PageBody>
      {q.isError ? (
        <ErrorNote error={q.error} />
      ) : overview.isError ? (
        <ErrorNote error={overview.error} />
      ) : q.isPending || overview.isPending ? (
        <LoadingRow />
      ) : (
        <Issue data={q.data} banners={overview.data.banners} />
      )}
    </PageBody>
  );
}
