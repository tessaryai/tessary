// SPDX-License-Identifier: Apache-2.0
import { useId } from "react";
import type { EndUserIssueRow } from "../../api/types";
import { ISSUE_GRID, IssueRow } from "./IssueRow";
import { ISSUE_COLUMNS, ISSUES_HEADING, NO_CASES, ROW_LEGEND } from "./usersWords";

/** Cases of one group become one row at the place of the first; the server's order is kept. */
function issueUnits(issues: EndUserIssueRow[]): EndUserIssueRow[][] {
  const units = new Map<string, EndUserIssueRow[]>();
  for (const row of issues) {
    const key = row.issue.group_id == null ? `case:${row.case_id}` : `group:${row.issue.group_id}`;
    const unit = units.get(key);
    if (unit) unit.push(row);
    else units.set(key, [row]);
  }
  return [...units.values()];
}

/** Board P1, lower part: the cases in the server's rank order. */
export function IssueList({ issues }: { issues: EndUserIssueRow[] }) {
  const headingId = useId();
  const units = issueUnits(issues);
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="text-h2 text-fg">
        {ISSUES_HEADING}
      </h2>
      <div className="rounded-card border border-border overflow-hidden bg-surface">
        {units.length === 0 ? (
          <p className="m-0 px-4.5 py-4 text-body text-muted">{NO_CASES}</p>
        ) : (
          <>
            <div className={`${ISSUE_GRID} text-small text-muted`}>
              <span>{ISSUE_COLUMNS.issue}</span>
              <span className="text-right whitespace-nowrap">{ISSUE_COLUMNS.hit}</span>
              <span className="flex flex-wrap items-center gap-x-3.5 gap-y-1">
                <span>{ISSUE_COLUMNS.curve}</span>
                <LegendKey swatch="bg-error" label={ROW_LEGEND.hit} />
                <LegendKey swatch="bg-muted" label={ROW_LEGEND.similar} />
              </span>
              <span />
            </div>
            <ul className="m-0 p-0 list-none">
              {units.map((rows) => (
                <li key={rows[0].case_id}>
                  <IssueRow rows={rows} />
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  );
}

function LegendKey({ swatch, label }: { swatch: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden className={`inline-block w-3.5 h-0.5 ${swatch}`} />
      {label}
    </span>
  );
}
