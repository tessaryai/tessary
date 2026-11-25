// SPDX-License-Identifier: Apache-2.0
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { EndUserOverview } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";
import { ErrorNote, LoadingRow, PageBody, PageHeader } from "../../ui";
import { FunnelCard } from "./FunnelCard";
import { HowCounted, HowCountedToggle } from "./HowCounted";
import { IssueList } from "./IssueList";
import { PageStateNotice } from "./PageStateNotice";
import { LimitLines, UsersBanners } from "./UsersBanners";
import { endUserQueries } from "./usersQueries";
import { PAGE_TITLE, UPDATED_DAILY } from "./usersWords";

function UpdatedDaily() {
  return (
    <span className="inline-flex items-center gap-1.5 rounded-pill border border-border-strong px-2.5 py-0.5 text-label text-fg-secondary">
      <span aria-hidden="true" className="inline-block size-1.5 rounded-pill bg-success" />
      {UPDATED_DAILY}
    </span>
  );
}

/** The server sends stages and the stop stage together: a refused page has neither, and its notice takes their place. */
function Top({ overview: o, how }: { overview: EndUserOverview; how: boolean }) {
  return (
    <>
      {how && <HowCounted overview={o} />}
      <UsersBanners banners={o.banners} />
      <PageStateNotice overview={o} />
      {o.stages && (
        <FunnelCard stages={o.stages} stopState={o.stop_stage_state!} stopNumbers={o.stop_stage_numbers!} />
      )}
      <LimitLines limits={o.limits} />
    </>
  );
}

/** Board P1: where the user IDs are, and the issues ranked by users lost. Each read fails on its own. */
export function UsersPage() {
  const api = useProjectApi();
  const overview = useQuery(endUserQueries.overview(api));
  const issues = useQuery(endUserQueries.issues(api));
  const [how, setHow] = useState(false);
  return (
    <PageBody>
      <PageHeader
        title={PAGE_TITLE}
        titleAside={<UpdatedDaily />}
        actions={<HowCountedToggle open={how} onToggle={() => setHow(!how)} />}
      />
      <div className="flex flex-col gap-6">
        {overview.isPending ? (
          <LoadingRow />
        ) : overview.isError ? (
          <ErrorNote error={overview.error} />
        ) : (
          <Top overview={overview.data} how={how} />
        )}
        {issues.isPending ? (
          <LoadingRow />
        ) : issues.isError ? (
          <ErrorNote error={issues.error} />
        ) : (
          <IssueList issues={issues.data.issues} />
        )}
      </div>
    </PageBody>
  );
}
