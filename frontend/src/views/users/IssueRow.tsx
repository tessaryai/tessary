// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import type { EndUserIssueRow } from "../../api/types";
import { RetentionChart } from "./chart/RetentionChart";
import { groupNote, groupRefs, groupTitle, issueRowLabel, thousands } from "./usersWords";

/** The columns of the issue list, shared by its header and its rows so they line up. */
export const ISSUE_GRID =
  "grid grid-cols-[minmax(0,1fr)_5.5rem_minmax(0,35rem)_0.875rem] items-center gap-5 px-4.5 py-2.5";

/**
 * Board P1: one issue, or one group of findings that fire together. `rows` are the group's cases in server order; they
 * share one set of numbers, and the first one names the issue page.
 */
export function IssueRow({ rows }: { rows: EndUserIssueRow[] }) {
  const lead = rows[0];
  const issue = lead.issue;
  const group = rows.length > 1;
  const refs = group ? groupRefs(rows.map((r) => r.case_ref)) : lead.case_ref;
  return (
    <Link
      to={`issues/${encodeURIComponent(lead.case_ref)}`}
      className={`${ISSUE_GRID} bg-surface hover:bg-hover transition-colors border-t border-border text-fg no-underline`}
    >
      <span className="min-w-0 flex flex-col gap-1">
        <span className="text-body font-medium">{group ? groupTitle(rows.map((r) => r.title)) : lead.title}</span>
        <span className="flex flex-wrap items-center gap-2 text-small text-muted">
          <span className="font-mono text-subtle">{refs}</span>
          <span className="inline-flex items-center gap-1.5">
            <svg aria-hidden viewBox="0 0 4 4" className="w-1 h-1">
              <circle cx={2} cy={2} r={1} fill="currentColor" />
            </svg>
            {group ? groupNote(rows.length) : lead.subject_label}
          </span>
        </span>
      </span>
      <span className="text-right text-body tabular-nums text-fg-secondary">{thousands(issue.user_ids_hit)}</span>
      {issue.curve ? (
        <RetentionChart variant="row" caseRef={refs} issue={{ ...issue, curve: issue.curve }} />
      ) : (
        <span className="py-2 text-small text-muted">{issueRowLabel(issue)}</span>
      )}
      <svg
        aria-hidden
        viewBox="0 0 24 24"
        className="w-3.5 h-3.5"
        fill="none"
        stroke="var(--color-muted)"
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="m9 18 6-6-6-6" />
      </svg>
    </Link>
  );
}
