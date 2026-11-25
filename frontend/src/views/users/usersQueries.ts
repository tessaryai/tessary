// SPDX-License-Identifier: Apache-2.0
import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import type { ProjectApi } from "../../api/client";
import type { EndUserStage } from "../../api/types";

/** User IDs per page of the ID list. */
export const ID_PAGE_SIZE = 50;

const root = (api: ProjectApi) => ["end-users", api.base] as const;
const nextCursor = (last: { next_cursor: string | null }) => last.next_cursor ?? undefined;

/** Every key starts with `["end-users", api.base]`, so a project switch reads again. */
export const endUserQueries = {
  overview: (api: ProjectApi) =>
    queryOptions({ queryKey: [...root(api), "overview"], queryFn: () => api.getEndUsersOverview() }),
  issues: (api: ProjectApi) => queryOptions({ queryKey: [...root(api), "issues"], queryFn: () => api.getEndUserIssues() }),
  issue: (api: ProjectApi, caseId: string) =>
    queryOptions({ queryKey: [...root(api), "issue", caseId], queryFn: () => api.getEndUserIssue(caseId) }),
  silentIds: (api: ProjectApi, caseId: string) =>
    infiniteQueryOptions({
      queryKey: [...root(api), "silent-ids", caseId],
      queryFn: ({ pageParam }) => api.getEndUserSilentIds(caseId, pageParam),
      initialPageParam: null as string | null,
      getNextPageParam: nextCursor,
    }),
  ids: (api: ProjectApi, stage: EndUserStage) =>
    infiniteQueryOptions({
      queryKey: [...root(api), "ids", stage],
      queryFn: ({ pageParam }) => api.getEndUserIds(stage, pageParam, ID_PAGE_SIZE),
      initialPageParam: null as string | null,
      getNextPageParam: nextCursor,
    }),
  caseBlock: (api: ProjectApi, caseId: string) =>
    queryOptions({ queryKey: [...root(api), "case", caseId], queryFn: () => api.getEndUserCaseBlock(caseId) }),
  hitCounts: (api: ProjectApi) =>
    queryOptions({ queryKey: [...root(api), "hit-counts"], queryFn: () => api.getEndUserHitCounts() }),
};
