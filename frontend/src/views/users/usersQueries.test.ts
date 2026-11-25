// SPDX-License-Identifier: Apache-2.0
/*
 * The Users view reads. The bugs worth catching: a key without the project base, so a project switch shows the last
 * project's user IDs; two case ids or two stages that share a key, so one issue's drawer shows another's IDs; and a
 * list that drops the cursor, so "+ N more" loads page one again, or that asks for a page after the last.
 */
import { QueryClient } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
import type { ProjectApi } from "../../api/client";
import { endUserQueries, ID_PAGE_SIZE } from "./usersQueries";

const page = (next_cursor: string | null) => ({ ids: [], next_cursor });

function stubApi(base: string) {
  return {
    base,
    getEndUsersOverview: vi.fn().mockResolvedValue({ page_state: "ok" }),
    getEndUserIssues: vi.fn().mockResolvedValue({ issues: [] }),
    getEndUserIssue: vi.fn().mockResolvedValue({ case_ref: "C-118" }),
    getEndUserCaseBlock: vi.fn().mockResolvedValue({ case_ref: "C-118" }),
    getEndUserHitCounts: vi.fn().mockResolvedValue({ cases: [] }),
    getEndUserSilentIds: vi.fn().mockResolvedValueOnce(page("c2")).mockResolvedValueOnce(page(null)),
    getEndUserIds: vi.fn().mockResolvedValueOnce(page("c2")).mockResolvedValueOnce(page(null)),
  };
}

const A = "/api/orgs/acme/projects/default";
const B = "/api/orgs/acme/projects/other";
const asApi = (stub: ReturnType<typeof stubApi>) => stub as unknown as ProjectApi;

it("keys every read under end-users and the project base, so a project switch reads again", () => {
  const a = asApi(stubApi(A));
  const b = asApi(stubApi(B));
  const keys = (api: ProjectApi) => [
    endUserQueries.overview(api).queryKey,
    endUserQueries.issues(api).queryKey,
    endUserQueries.issue(api, "C-118").queryKey,
    endUserQueries.silentIds(api, "C-118").queryKey,
    endUserQueries.ids(api, "stopped").queryKey,
    endUserQueries.caseBlock(api, "C-118").queryKey,
    endUserQueries.hitCounts(api).queryKey,
  ];

  for (const key of keys(a)) expect(key.slice(0, 2)).toEqual(["end-users", A]);
  for (const key of keys(b)) expect(key.slice(0, 2)).toEqual(["end-users", B]);
  expect(new Set(keys(a).map((k) => JSON.stringify(k))).size).toBe(7);
});

it("gives each case and each stage its own key", () => {
  const api = asApi(stubApi(A));

  expect(endUserQueries.issue(api, "C-118").queryKey).not.toEqual(endUserQueries.issue(api, "C-121").queryKey);
  expect(endUserQueries.silentIds(api, "C-118").queryKey).not.toEqual(endUserQueries.silentIds(api, "C-121").queryKey);
  expect(endUserQueries.caseBlock(api, "C-118").queryKey).not.toEqual(endUserQueries.caseBlock(api, "C-121").queryKey);
  expect(endUserQueries.ids(api, "silent").queryKey).not.toEqual(endUserQueries.ids(api, "stopped").queryKey);
});

it("reads the overview, the issues, one issue, the case block and the hit counts from their own routes", async () => {
  const stub = stubApi(A);
  const api = asApi(stub);
  const qc = new QueryClient();

  await expect(qc.fetchQuery(endUserQueries.overview(api))).resolves.toEqual({ page_state: "ok" });
  await expect(qc.fetchQuery(endUserQueries.issues(api))).resolves.toEqual({ issues: [] });
  await qc.fetchQuery(endUserQueries.issue(api, "C-118"));
  await qc.fetchQuery(endUserQueries.caseBlock(api, "C-121"));
  await expect(qc.fetchQuery(endUserQueries.hitCounts(api))).resolves.toEqual({ cases: [] });

  expect(stub.getEndUserIssue).toHaveBeenCalledWith("C-118");
  expect(stub.getEndUserCaseBlock).toHaveBeenCalledWith("C-121");
});

it("pages the ID list by its cursor, 50 at a time, and stops at the last page", async () => {
  const stub = stubApi(A);
  const query = endUserQueries.ids(asApi(stub), "stopped");
  const qc = new QueryClient();

  const data = await qc.fetchInfiniteQuery({ ...query, pages: 3 });

  expect(ID_PAGE_SIZE).toBe(50);
  expect(stub.getEndUserIds.mock.calls).toEqual([
    ["stopped", null, 50],
    ["stopped", "c2", 50],
  ]);
  expect(query.getNextPageParam(data.pages[1], data.pages, "c2", data.pageParams)).toBeUndefined();
});

it("pages the silent hit user IDs of one case by its cursor", async () => {
  const stub = stubApi(A);
  const query = endUserQueries.silentIds(asApi(stub), "C-118");
  const qc = new QueryClient();

  await qc.fetchInfiniteQuery({ ...query, pages: 2 });

  expect(stub.getEndUserSilentIds.mock.calls).toEqual([
    ["C-118", null],
    ["C-118", "c2"],
  ]);
});
