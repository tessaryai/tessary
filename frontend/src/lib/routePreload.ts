// SPDX-License-Identifier: Apache-2.0
// Warms a route's chunk while `/auth/me` is in flight, since ProtectedRoute renders nothing until
// it answers. Its own module so AuthContext and App.tsx need not import each other. Moves no data.

const preloaders = new Map<string, () => Promise<unknown>>();

export function registerRouteChunk(segment: string, loader: () => Promise<unknown>): void {
  preloaders.set(segment, loader);
}

/** A miss is silent and a rejected import is swallowed: React.lazy retries and reports it on render. */
export function preloadRouteChunk(pathname: string): void {
  // ["", "orgs", ":org", "projects", ":project", "<segment>", ...]
  const segment = pathname.split("/")[5];
  if (!segment) return;
  const load = preloaders.get(segment);
  if (!load) return;
  void load().catch(() => {
  });
}
