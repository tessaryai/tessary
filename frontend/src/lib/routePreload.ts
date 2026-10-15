// SPDX-License-Identifier: Apache-2.0
// Warms a route's chunk while `/auth/me` is in flight, since ProtectedRoute renders nothing until
// it answers. Its own module so main.tsx (which starts a preload at boot) and App.tsx (which registers
// the loaders) have no evaluation-order question. Moves no data: shell/shellQueries.ts prefetches that.

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

/** A chunk's loader, plus the module once any call to it has landed. */
export function trackedChunk<M>(loader: () => Promise<M>): { load: () => Promise<M>; loaded: () => M | undefined } {
  let module: M | undefined;
  return {
    load: () =>
      loader().then((m) => {
        module = m;
        return m;
      }),
    loaded: () => module,
  };
}
