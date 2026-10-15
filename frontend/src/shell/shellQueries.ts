// SPDX-License-Identifier: Apache-2.0
/*
 * The reads every project screen waits on before it can draw, and the boot-time prefetch that starts
 * them in the same tick as the sign-in check.
 *
 * Without the prefetch they form a ladder: `/auth/me`, then the project list and the substrate
 * status (ProjectShell will not pick between the connect gate and the shell without them), then the
 * capabilities and the cases (the sidebar and Triage), each rung a full round trip after the one
 * before. None of them depends on the answer to the one before it: the server authorises each on its
 * own, and a signed-out visitor gets a 401 from each and is sent to sign in by ProtectedRoute as
 * before. Each hook below reads the same options, so the key and the fetch exist once.
 */
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
