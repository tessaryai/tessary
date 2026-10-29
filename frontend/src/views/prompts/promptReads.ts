// SPDX-License-Identifier: Apache-2.0
import { useQuery, type QueryClient } from "@tanstack/react-query";
import type { PromptListParams } from "../../api/client";
import { useProjectApi } from "../../tenant/TenantContext";

/** The list reads page 1 at this limit and says when `total` is larger. */
export const PROMPT_LIST_LIMIT = 200;

/** The list page's filters; it always reads page 1 at `PROMPT_LIST_LIMIT`. */
export type PromptListFilters = Pick<PromptListParams, "folder" | "q" | "label" | "type">;

export const promptKeys = {
  list: (base: string, p: PromptListFilters) => ["prompts", base, p] as const,
  usageSummary: (base: string, days: number) => ["prompt-usage-summary", base, days] as const,
  detail: (base: string, id: string) => ["prompt", base, id] as const,
  version: (base: string, id: string, v: number) => ["prompt-version", base, id, v] as const,
  usage: (base: string, id: string, days: number) => ["prompt-usage", base, id, days] as const,
  traces: (base: string, name: string, v: number) => ["prompt-traces", base, name, v] as const,
};

export function usePromptList(p: PromptListFilters) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.list(api.base, p),
    queryFn: () => api.listPrompts({ ...p, page: 1, limit: PROMPT_LIST_LIMIT }),
  });
}

export function usePromptUsageSummary(days: number) {
  const api = useProjectApi();
  return useQuery({ queryKey: promptKeys.usageSummary(api.base, days), queryFn: () => api.getPromptUsageSummary(days) });
}

export function usePrompt(id: string) {
  const api = useProjectApi();
  return useQuery({ queryKey: promptKeys.detail(api.base, id), queryFn: () => api.getPrompt(id) });
}

export function usePromptVersion(id: string, v: number, enabled: boolean) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.version(api.base, id, v),
    queryFn: () => api.getPromptVersion(id, v),
    enabled,
  });
}

/** Mounted only on the Usage tab, after the version read succeeds. */
export function usePromptUsage(id: string, days: number) {
  const api = useProjectApi();
  return useQuery({ queryKey: promptKeys.usage(api.base, id, days), queryFn: () => api.getPromptUsage(id, days) });
}

/** Mounted only on the Traces tab, after the version read succeeds. */
export function usePromptTraces(name: string, v: number) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.traces(api.base, name, v),
    queryFn: () => api.listTraces({ promptName: name, promptVersion: String(v), limit: 50, sort: "when" }),
  });
}

/** Every list read, and the detail, version and usage reads of one prompt, or of every prompt when `promptId` is absent. */
export function invalidatePromptReads(qc: QueryClient, base: string, promptId?: string): void {
  const one = promptId === undefined ? [] : [promptId];
  for (const root of ["prompts", "prompt-usage-summary"]) qc.invalidateQueries({ queryKey: [root, base] });
  for (const root of ["prompt", "prompt-version", "prompt-usage"]) qc.invalidateQueries({ queryKey: [root, base, ...one] });
}
