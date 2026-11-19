// SPDX-License-Identifier: Apache-2.0
import { keepPreviousData, useInfiniteQuery, useQuery, type QueryClient } from "@tanstack/react-query";
import type { PromptLevelParams } from "../../api/client";
import type { PromptDetail } from "../../api/types";
import { useProjectApi } from "../../tenant/TenantContext";

/** Prompts per page of the list, folder level or search alike. */
export const PROMPT_PAGE_SIZE = 50;
/** Versions per page of the detail page's version list. */
export const VERSION_PAGE_SIZE = 100;

/** The list page's folder-level filters; `folder` absent is the root. */
export type PromptLevelFilters = Pick<PromptLevelParams, "folder" | "label" | "tag">;
/** A search lists matching names flat, across every folder under `folder` (every folder when absent). */
export type PromptSearchFilters = { q: string; folder?: string; label?: string; tag?: string };

/** Every key starts with its root and the project base, which is what `invalidatePromptReads` drops. */
export const promptKeys = {
  level: (base: string, p: PromptLevelFilters) => ["prompts", base, "level", p] as const,
  search: (base: string, p: PromptSearchFilters) => ["prompts", base, "search", p] as const,
  facets: (base: string) => ["prompts", base, "facets"] as const,
  usageSummary: (base: string, days: number) => ["prompt-usage-summary", base, days] as const,
  detail: (base: string, id: string) => ["prompt", base, id] as const,
  /** `below` is the oldest version of the detail page, so a write that moves it starts the older pages afresh. */
  olderVersions: (base: string, id: string, below: number | undefined) => ["prompt", base, id, "older", below] as const,
  version: (base: string, id: string, v: number) => ["prompt-version", base, id, v] as const,
  usage: (base: string, id: string, days: number) => ["prompt-usage", base, id, days] as const,
  traces: (base: string, name: string, v: number) => ["prompt-traces", base, name, v] as const,
};

const nextPage = (last: { page: number; limit: number; total: number }) =>
  last.page * last.limit < last.total ? last.page + 1 : undefined;

/** One folder level, a page of prompts at a time; every page repeats the child folders, and the first one is shown. */
export function usePromptLevel(p: PromptLevelFilters, enabled: boolean) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: promptKeys.level(api.base, p),
    queryFn: ({ pageParam }) => api.listPromptLevel({ ...p, page: pageParam, limit: PROMPT_PAGE_SIZE }),
    initialPageParam: 1,
    getNextPageParam: nextPage,
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function usePromptSearch(p: PromptSearchFilters, enabled: boolean) {
  const api = useProjectApi();
  return useInfiniteQuery({
    queryKey: promptKeys.search(api.base, p),
    queryFn: ({ pageParam }) => api.listPrompts({ ...p, page: pageParam, limit: PROMPT_PAGE_SIZE }),
    initialPageParam: 1,
    getNextPageParam: nextPage,
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function usePromptFacets() {
  const api = useProjectApi();
  return useQuery({ queryKey: promptKeys.facets(api.base), queryFn: () => api.getPromptFacets() });
}

/** Calls per prompt over the window. It counts every linked span, so a filter change on the list does not read it again. */
export const USAGE_SUMMARY_STALE_MS = 5 * 60_000;

export function usePromptUsageSummary(days: number) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.usageSummary(api.base, days),
    queryFn: () => api.getPromptUsageSummary(days),
    staleTime: USAGE_SUMMARY_STALE_MS,
  });
}

/** The prompt with its newest `VERSION_PAGE_SIZE` versions. */
export function usePrompt(id: string) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.detail(api.base, id),
    queryFn: () => api.getPrompt(id, { limit: VERSION_PAGE_SIZE }),
  });
}

/**
 * The versions older than the detail read's page, a page at a time. Nothing is read until the reader asks
 * (`enabled`), and each page starts below the oldest version already shown. A refetch reuses the first page param,
 * so that version is in the key: after a create or delete the older pages start again below the new detail page.
 */
export function useOlderVersions(detail: PromptDetail, enabled: boolean) {
  const api = useProjectApi();
  const oldest = detail.versions.at(-1)?.version;
  return useInfiniteQuery({
    queryKey: promptKeys.olderVersions(api.base, detail.id, oldest),
    queryFn: ({ pageParam }) => api.getPrompt(detail.id, { limit: VERSION_PAGE_SIZE, beforeVersion: pageParam }),
    initialPageParam: oldest ?? 0,
    getNextPageParam: (last) => (last.has_older_versions ? last.versions.at(-1)?.version : undefined),
    enabled: enabled && oldest !== undefined && detail.has_older_versions,
  });
}

export function usePromptVersion(id: string, v: number, enabled: boolean) {
  const api = useProjectApi();
  return useQuery({
    queryKey: promptKeys.version(api.base, id, v),
    queryFn: () => api.getPromptVersion(id, v),
    enabled,
  });
}

/** Mounted only on the Metrics tab. */
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
