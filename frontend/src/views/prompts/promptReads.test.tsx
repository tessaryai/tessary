// SPDX-License-Identifier: Apache-2.0
/*
 * One invalidation covers every prompt write. The bugs worth catching are a write that leaves the list or the open
 * version stale, and one that names the prompt in the wrong key position, so the detail it just changed stays cached.
 * The list's usage summary is the costly read, so it also must not run again on every filter change.
 */
import type { ReactNode } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { api } from "../../test/api";
import { promptReadsStale, seedPromptReads } from "../../test/promptReads";
import type { PromptDetail } from "../../api/types";
import { invalidatePromptReads, useOlderVersions, usePrompt, usePromptUsageSummary } from "./promptReads";

vi.mock("../../tenant/TenantContext", () => import("../../test/api"));

afterEach(() => vi.useRealTimers());

const BASE = "/api/orgs/acme/projects/default";

it("marks the list, the prompt and its versions stale after a write to that prompt, and no other prompt", () => {
  const qc = new QueryClient();
  seedPromptReads(qc);
  invalidatePromptReads(qc, BASE, "p-2");
  expect(promptReadsStale(qc)).toEqual([true, false, false]);

  invalidatePromptReads(qc, BASE, "p-1");
  expect(promptReadsStale(qc)).toEqual([true, true, true]);
});

it("marks every prompt's detail stale when no prompt is named", () => {
  const qc = new QueryClient();
  seedPromptReads(qc);

  invalidatePromptReads(qc, BASE);

  expect(promptReadsStale(qc)).toEqual([true, true, true]);
});

// Bug: the summary counts every linked span in the window, and each filter change on the list remounted the read and
// ran it again.
it("reads the usage summary once in five minutes", async () => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(new Date("2026-11-18T10:00:00Z"));
  api.getPromptUsageSummary.mockResolvedValue({ days: 7, items: [] });
  const qc = new QueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  const mount = async () => {
    const hook = renderHook(() => usePromptUsageSummary(7), { wrapper });
    await waitFor(() => expect(hook.result.current.isSuccess).toBe(true));
    hook.unmount();
  };

  await mount();
  vi.setSystemTime(new Date("2026-11-18T10:04:59Z"));
  await mount();
  expect(api.getPromptUsageSummary).toHaveBeenCalledTimes(1);

  vi.setSystemTime(new Date("2026-11-18T10:05:01Z"));
  await mount();
  await waitFor(() => expect(api.getPromptUsageSummary).toHaveBeenCalledTimes(2));
});

/** A server holding `numbers`, which answers the detail read as the real route pages it: newest first, 100 a page. */
function serveVersions(numbers: number[]) {
  api.getPrompt.mockImplementation(async (_id: string, p: { limit: number; beforeVersion?: number }) => {
    const below = [...numbers].sort((a, b) => b - a).filter((n) => p.beforeVersion === undefined || n < p.beforeVersion);
    return {
      id: "p-1",
      versions: below.slice(0, p.limit).map((n) => ({ id: `v-${n}`, version: n, labels: [] })),
      has_older_versions: below.length > p.limit,
    };
  });
}

const NO_DETAIL = { id: "p-1", versions: [], has_older_versions: false } as unknown as PromptDetail;

/** The version list as the detail page builds it: the detail's page, then every older page. */
function useShownVersions() {
  const detail = usePrompt("p-1");
  const older = useOlderVersions(detail.data ?? NO_DETAIL, true);
  if (!detail.data || older.isFetching) return null;
  return [...detail.data.versions, ...(older.data?.pages ?? []).flatMap((p) => p.versions)].map((v) => v.version);
}

const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => to - i);

// Bug: the older pages kept their first page param across a write, so after a create the version just below the
// detail page showed in neither list, and after a delete one version showed twice. Two pages stay loaded, so after a
// create v1 moves behind "Show older versions".
it.each([
  ["a create", (n: number[]) => [...n, 201], range(2, 201)],
  ["a delete", (n: number[]) => n.filter((v) => v !== 150), range(1, 200).filter((v) => v !== 150)],
])("shows every version once after %s on a prompt with older pages open", async (_write, change, expected) => {
  let numbers = range(1, 200);
  serveVersions(numbers);
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  const hook = renderHook(() => useShownVersions(), { wrapper });
  await waitFor(() => expect(hook.result.current).toEqual(range(1, 200)));

  numbers = change(numbers);
  serveVersions(numbers);
  await invalidatePromptReads(qc, BASE, "p-1");

  await waitFor(() => expect(hook.result.current).toEqual(expected));
});
