// SPDX-License-Identifier: Apache-2.0
import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import type { EndUserGrade, EndUserSilentId, EndUserVerdict } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { ErrorNote, Rail, Table, TableSkeleton, TBody, TD, TH, THead, TR } from "../../ui";
import { endUserQueries } from "./usersQueries";
import {
  moreSilent,
  NO_ID_TEXT,
  railHeader,
  SILENT_COLUMNS,
  SILENT_NOTE,
  SILENT_NOW,
  silentNowCount,
  silentState,
  weeksAgo,
} from "./usersWords";

type WeekGroup = { low: number; high: number; rows: EndUserSilentId[] };

/** Board P3 puts weeks 6 and 7 in one group and 5 and 8 in groups of their own: groups span two weeks, 2k and 2k+1. */
const span = (weeks: number) => Math.floor(weeks / 2);

/** The server sends the rows oldest hit first, so one span's rows are adjacent. */
function byWeeks(rows: EndUserSilentId[]): WeekGroup[] {
  const groups: WeekGroup[] = [];
  for (const r of rows) {
    const w = r.weeks_since_hit;
    const last = groups[groups.length - 1];
    if (last && span(last.high) === span(w)) {
      last.rows.push(r);
      last.low = Math.min(last.low, w);
      last.high = Math.max(last.high, w);
    } else {
      groups.push({ low: w, high: w, rows: [r] });
    }
  }
  return groups;
}

const STATE_TONE: Record<EndUserGrade | "none", string> = {
  stopped: "text-error",
  probably_stopped: "text-fg-secondary",
  unattended: "text-muted",
  not_yet_checked: "text-muted",
  none: "text-muted",
};

function IdRow({ r }: { r: EndUserSilentId }) {
  return (
    <TR>
      <TD className="font-mono">
        {r.id_text == null ? (
          <span className="text-muted">{NO_ID_TEXT}</span>
        ) : (
          <Link to={`../traces?q=${encodeURIComponent(r.id_text)}`} className="text-fg hover:text-link">
            {r.id_text}
          </Link>
        )}
      </TD>
      <TD className="text-right tabular-nums text-fg-secondary">{r.days_silent}</TD>
      <TD className={STATE_TONE[r.grade ?? "none"]}>{silentState(r)}</TD>
    </TR>
  );
}

function SilentIds({ caseRef }: { caseRef: string }) {
  const api = useProjectApi();
  const list = useInfiniteQuery(endUserQueries.silentIds(api, caseRef));
  if (list.isPending) return <TableSkeleton cols={3} />;
  if (list.isError) return <ErrorNote error={list.error} />;
  const pages = list.data.pages;
  const last = pages[pages.length - 1];
  return (
    <div className="flex flex-col gap-3">
      <Table>
        <THead>
          <TR>
            <TH>{SILENT_COLUMNS.id}</TH>
            <TH className="text-right">{SILENT_COLUMNS.days}</TH>
            <TH>{SILENT_COLUMNS.state}</TH>
          </TR>
        </THead>
        {byWeeks(pages.flatMap((p) => p.ids)).map((g) => (
          <TBody key={g.high}>
            <TR>
              <th
                scope="rowgroup"
                colSpan={3}
                className="px-3 pt-3 pb-1 text-left font-mono text-label uppercase text-muted"
              >
                {weeksAgo(g.low, g.high)}
              </th>
            </TR>
            {g.rows.map((r, i) => (
              <IdRow key={`${r.id_text}-${i}`} r={r} />
            ))}
          </TBody>
        ))}
      </Table>
      {list.hasNextPage && (
        <button
          type="button"
          disabled={list.isFetchingNextPage}
          onClick={() => list.fetchNextPage()}
          className="self-start bg-transparent text-small text-muted hover:text-fg"
        >
          {moreSilent(last.more, last.more_weeks_low!, last.more_weeks_high!)}
        </button>
      )}
    </div>
  );
}

/** Board P3: the hit user IDs of a claimed issue that are silent now, grouped by the weeks since their hit. */
export function SilentIdsRail({
  caseRef,
  verdict,
  silentNow,
  onClose,
}: {
  caseRef: string;
  verdict: EndUserVerdict;
  silentNow: number;
  onClose: () => void;
}) {
  return (
    <Rail
      open
      onClose={onClose}
      title={silentNowCount(silentNow)}
      meta={railHeader(caseRef, verdict)}
      aria-label={SILENT_NOW}
    >
      <div className="flex flex-col gap-4">
        <p className="m-0 rounded-control bg-raised px-3 py-2.5 text-small text-fg-secondary">{SILENT_NOTE}</p>
        <SilentIds caseRef={caseRef} />
      </div>
    </Rail>
  );
}
