// SPDX-License-Identifier: Apache-2.0
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import type { EndUserIdRow, EndUserOverview, EndUserStage, EndUserStageCounts } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import {
  Badge,
  cn,
  ErrorNote,
  LoadingRow,
  PageBody,
  PageHeader,
  Table,
  TableSkeleton,
  TabNav,
  TBody,
  TD,
  TH,
  THead,
  TR,
} from "../../ui";
import { PageStateNotice } from "./PageStateNotice";
import { endUserQueries } from "./usersQueries";
import {
  ACTIVE_EXPLAIN,
  activeHeader,
  FUNNEL_HEADING,
  HIT_BY_NOTE,
  ID_COLUMNS,
  idState,
  idWhy,
  more,
  NO_ID_TEXT,
  PAGE_TITLE,
  SILENT_EXPLAIN,
  silentHeader,
  STAGE_LABELS,
  stageTab,
  STOPPED_EXPLAIN,
  stoppedHeader,
  stopStage,
} from "./usersWords";

const STAGES: EndUserStage[] = ["active", "silent", "stopped"];

/** The stopped list shows only while the stage names a count; the other states withhold it (level 2). */
const NAMED_STATES: ReadonlySet<EndUserOverview["stop_stage_state"]> = new Set([
  "expected_and_named",
  "cannot_be_named_yet",
]);

const toStage = (raw: string | null): EndUserStage =>
  STAGES.includes(raw as EndUserStage) ? (raw as EndUserStage) : "active";

const trail = (stage: EndUserStage) => [{ label: PAGE_TITLE, to: "../users" }, { label: STAGE_LABELS[stage] }];

function counts(stages: EndUserStageCounts, named: boolean): Record<EndUserStage, number | null> {
  return { active: stages.active, silent: stages.silent, stopped: named ? stages.stopped_named : null };
}

function Lead({ lead, explain }: { lead: string; explain: string }) {
  return (
    <>
      <span className="text-fg">{lead}</span> {explain}
    </>
  );
}

function IdRow({ r }: { r: EndUserIdRow }) {
  return (
    <TR>
      <TD className={cn("font-mono", r.id_text == null && "text-muted")}>{r.id_text ?? NO_ID_TEXT}</TD>
      <TD className={r.grade === "stopped" ? "text-error" : "text-fg-secondary"}>{idState(r)}</TD>
      <TD className="text-fg-secondary">{idWhy(r)}</TD>
      <TD>
        <span className="flex flex-wrap gap-1">
          {r.hit_by.map((ref) => (
            <Link key={ref} to={`../users/issues/${encodeURIComponent(ref)}`} className="no-underline">
              <Badge tone="error" className="font-mono">
                {ref}
              </Badge>
            </Link>
          ))}
        </span>
      </TD>
    </TR>
  );
}

function IdList({ stage, total }: { stage: EndUserStage; total: number }) {
  const api = useProjectApi();
  const list = useInfiniteQuery(endUserQueries.ids(api, stage));
  if (list.isPending) return <TableSkeleton cols={4} />;
  if (list.isError) return <ErrorNote error={list.error} />;
  const rows = list.data.pages.flatMap((p) => p.ids);
  return (
    <Table>
      <THead>
        <TR>
          <TH>{ID_COLUMNS.id}</TH>
          <TH>{ID_COLUMNS.state}</TH>
          <TH>{ID_COLUMNS.why}</TH>
          <TH>{ID_COLUMNS.hitBy}</TH>
        </TR>
      </THead>
      <TBody>
        {rows.map((r, i) => (
          <IdRow key={`${r.id_text}-${i}`} r={r} />
        ))}
      </TBody>
      {list.hasNextPage && (
        <tfoot className="border-t border-border">
          <TR>
            <TD colSpan={4}>
              <button
                type="button"
                disabled={list.isFetchingNextPage}
                onClick={() => list.fetchNextPage()}
                className="text-small text-muted hover:text-fg"
              >
                {more(total - rows.length)}
              </button>
            </TD>
          </TR>
        </tfoot>
      )}
    </Table>
  );
}

function StageView({ overview, stage }: { overview: EndUserOverview; stage: EndUserStage }) {
  const stages = overview.stages!;
  const named = NAMED_STATES.has(overview.stop_stage_state);
  const n = counts(stages, named);
  const subtitle = {
    active: <Lead lead={activeHeader(stages.active, stages.new)} explain={ACTIVE_EXPLAIN} />,
    silent: <Lead lead={silentHeader(stages.silent)} explain={SILENT_EXPLAIN} />,
    stopped: named ? (
      <Lead lead={stoppedHeader(stages.stopped_named, stages.stopped_expected)} explain={STOPPED_EXPLAIN} />
    ) : (
      stopStage(overview.stop_stage_state!, overview.stop_stage_numbers!).note
    ),
  }[stage];
  const total = n[stage];
  return (
    <>
      <PageHeader
        breadcrumb={trail(stage)}
        title={STAGE_LABELS[stage]}
        subtitle={subtitle}
      />
      <TabNav
        label={FUNNEL_HEADING}
        variant="pill"
        value={stage}
        items={STAGES.map((s) => ({ value: s, label: stageTab(s, n[s]), to: `?stage=${s}` }))}
        className="mb-6"
      />
      {total != null && (
        <div className="flex flex-col gap-6">
          <IdList stage={stage} total={total} />
          <p className="text-small text-muted">{HIT_BY_NOTE}</p>
        </div>
      )}
    </>
  );
}

/** Board P5: the user IDs of one stage, one tab per stage in the URL. */
export function UserIdsPage() {
  const api = useProjectApi();
  const [params] = useSearchParams();
  const stage = toStage(params.get("stage"));
  const overview = useQuery(endUserQueries.overview(api));
  return (
    <PageBody>
      {overview.isPending ? (
        <LoadingRow />
      ) : overview.isError ? (
        <ErrorNote error={overview.error} />
      ) : overview.data.stages == null ? (
        <>
          <PageHeader breadcrumb={trail(stage)} title={STAGE_LABELS[stage]} />
          <PageStateNotice overview={overview.data} />
        </>
      ) : (
        <StageView overview={overview.data} stage={stage} />
      )}
    </PageBody>
  );
}
