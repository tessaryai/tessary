// SPDX-License-Identifier: Apache-2.0
/*
 * The OPEN edition's answer to the paid seam: nothing, spelled out.
 *
 * This file is what `@paid` resolves to in this tree — see `contract.ts` next door for why the seam
 * exists and why it points inward rather than at the overlay. A commercial build overrides the one
 * `@paid` alias to its own registry; this module is not a placeholder for a missing file, it is the
 * open edition's real and complete answer, and it ships.
 *
 * Every export is an empty value or a function returning `null`, and that is deliberately
 * indistinguishable from a paid surface withheld — the same convention the backend's
 * `ObjectProvider` seams follow (`FitReportSource`, `SlackMentionSource`): absence and withholding
 * answer identically, so an open build is never a broken paid build.
 *
 * Nothing here is conditional and nothing is dynamic. There is no `import()`, no
 * `import.meta.env` probe and no try/catch around a missing module: whichever registry the build
 * aliased in is the one that ships, decided at build time, so the open bundle cannot fetch a chunk
 * that does not exist.
 */
import type { PaidSurfaces } from "./contract";

export const paid: PaidSurfaces = {
  // `/pricing` and `settings/usage` are not gated in this edition — they simply are not built.
  // `App.tsx` redirects `/pricing` to `/` so the public URL still resolves (its top-level
  // `<Routes>` has no catch-all, so an unmatched path there renders a BLANK page, not a fallback).
  publicRoutes: [],
  // `/new-org` (#862) is not built in this edition -- the single-org cap means there is never a
  // second org to create, so there is nothing for this route to lead to.
  protectedRoutes: [],
  settingsRoutes: [],
  // Read by `shell/nav.tsx` for both the Settings rail and the ⌘K palette, so a surface that is not
  // built is also not offered.
  settingsNav: {},
  debugSection: () => null,
  classifierRail: () => null,
  findingEvidence: () => null,
  // Renders nothing -- the open Sidebar's project switcher shows only the Projects section, with no
  // "+ New organization" action pointing at the route this edition doesn't build.
  orgSwitcherRows: () => null,
  // Renders nothing -- the open Organization settings page keeps rename/members/invitations/
  // projects/observer-settings and loses only transfer-ownership and archive/delete, whose backend
  // routes this edition's OrganizationController does not have.
  orgLifecycleSections: () => null,
};
