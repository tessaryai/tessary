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
import { invalidatePromptReads, usePromptUsageSummary } from "./promptReads";

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
