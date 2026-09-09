// SPDX-License-Identifier: Apache-2.0
/*
 * What `@paid` resolves to in this tree: every export is an empty value or a function returning
 * `null`. A build may override the `@paid` alias to a different registry; nothing here is
 * conditional or dynamic, so whichever registry the build aliased in is the one that ships.
 */
import type { PaidSurfaces } from "./contract";

export const paid: PaidSurfaces = {
  // `App.tsx` redirects `/pricing` to `/`, so the public URL still resolves rather than hitting
  // the top-level `<Routes>`'s missing catch-all (which would render a blank page).
  publicRoutes: [],
  // The single-org cap means there is never a second org to create, so `/new-org` leads nowhere.
  protectedRoutes: [],
  settingsRoutes: [],
  // Read by `shell/nav.tsx` for both the Settings rail and the ⌘K palette.
  settingsNav: {},
  debugSection: () => null,
  classifierRail: () => null,
  findingEvidence: () => null,
  orgSwitcherRows: () => null,
  orgLifecycleSections: () => null,
};
