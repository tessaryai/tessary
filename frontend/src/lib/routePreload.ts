// SPDX-License-Identifier: Apache-2.0
/*
 * Route chunk warm-up.
 *
 * Lives in its own module rather than in App.tsx on purpose: main.tsx STARTS a preload at boot and
 * App.tsx REGISTERS the loaders, and a module both can depend on has no evaluation-order question.
 *
 * WHY THIS EXISTS. `ProtectedRoute` renders nothing until `/auth/me` answers, so the route subtree —
 * and therefore React.lazy's dynamic import — could not begin until that round trip finished. That
 * put every route's chunk download strictly after the sign-in check for no reason: the bytes do not
 * depend on who you are, and the server authorises every request on its own regardless (AuthFilter,
 * then TenantPathResolver). Measured on production, the Graders chunk alone was 993 ms sitting in
 * that queue.
 *
 * It only warms the module cache. The shell's data reads are started beside it by
 * `shell/shellQueries.ts#prefetchProjectShell`.
 */

/** Route segment → the dynamic import that fetches its chunk. */
const preloaders = new Map<string, () => Promise<unknown>>();

/**
 * Register a view's loader against the first path segment under
 * `/orgs/:org/projects/:project/`. Called from App.tsx's lazy-route declarations.
 */
export function registerRouteChunk(segment: string, loader: () => Promise<unknown>): void {
  preloaders.set(segment, loader);
}

/**
 * Start fetching the chunk for `pathname`, if one is registered for it.
 *
 * A miss is silent and free — an unregistered route simply loads the way it always did — and a
 * rejected import is swallowed, because React.lazy will retry on render and report the failure
 * properly there. Nothing about correctness depends on this succeeding.
 */
export function preloadRouteChunk(pathname: string): void {
  // ["", "orgs", ":org", "projects", ":project", "<segment>", ...]
  const segment = pathname.split("/")[5];
  if (!segment) return;
  const load = preloaders.get(segment);
  if (!load) return;
  void load().catch(() => {
    /* warm-up only; React.lazy owns the real error path */
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
