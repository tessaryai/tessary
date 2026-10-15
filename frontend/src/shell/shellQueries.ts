// SPDX-License-Identifier: Apache-2.0
// The reads every project screen waits on, started at boot beside `/auth/me` instead of each a round
// trip after the one before. None needs the previous answer: the server authorises each on its own.
import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { auth, projectApi, type ProjectApi } from "../api/client";

export const projectsQuery = (orgSlug: string) =>
  queryOptions({ queryKey: ["projects", orgSlug], queryFn: () => auth.listProjects(orgSlug) });

export const capabilitiesQuery = (orgSlug: string) =>
  queryOptions({ queryKey: ["capabilities", orgSlug], queryFn: () => auth.getCapabilities(orgSlug) });

export const substrateStatusQuery = (api: ProjectApi) =>
  queryOptions({ queryKey: ["substrate-status", api.base], queryFn: api.substrateStatus });

export const casesQuery = (api: ProjectApi) =>
  queryOptions({ queryKey: ["cases", api.base], queryFn: api.getTriage });

/** Start the shell's reads for `pathname` if it is a project page. Fire-and-forget. */
export function prefetchProjectShell(queryClient: QueryClient, pathname: string): void {
  // ["", "orgs", ":org", "projects", ":project", ...]
  const [, orgs, org, projects, project] = pathname.split("/");
  if (orgs !== "orgs" || projects !== "projects" || !org || !project) return;
  const orgSlug = decodeURIComponent(org);
  const api = projectApi(orgSlug, decodeURIComponent(project));
  // No retry: a failed prefetch leaves the entry in error, and the hook that mounts refetches it.
  void queryClient.prefetchQuery({ ...projectsQuery(orgSlug), retry: false });
  void queryClient.prefetchQuery({ ...capabilitiesQuery(orgSlug), retry: false });
  void queryClient.prefetchQuery({ ...substrateStatusQuery(api), retry: false });
  void queryClient.prefetchQuery({ ...casesQuery(api), retry: false });
}
