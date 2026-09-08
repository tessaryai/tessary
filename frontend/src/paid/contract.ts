// SPDX-License-Identifier: Apache-2.0
/*
 * The paid frontend seam: the SHAPE of what a commercial build adds, declared in the open tree.
 *
 * <h2>Why a seam at all, rather than an import</h2>
 * Open-core decision D2 says the public export is a folder filter — delete the paid overlay
 * directory and the rest of the tree must still build. Backend code gets that property from Maven (an open module
 * cannot declare a dependency on `ai.tessary.paid:*`, and a Java class cannot be imported without
 * the dependency that carries it). The frontend has no such enforcer: TypeScript will not fail a
 * build because a paid view is missing, and a dynamic `import()` of an absent chunk is a RUNTIME
 * error, on the user's screen, with every gate green. The only machine guard is
 * `scripts/check-open-boundary.sh` rule 2, which greps `frontend/src` and the build config for the
 * overlay's own directory name — this file included — and bans BOTH obvious mechanisms: a
 * relative dynamic import, and a path alias in `vite.config.ts` / `tsconfig.json` pointing out
 * of the tree.
 *
 * So the seam inverts the direction the way every other extraction in this epic did. Open source
 * imports `@paid`, an alias that resolves INSIDE `frontend/src` to the stub next door
 * (`index.ts`, next to this file). A paid build overrides that one alias to the overlay's registry.
 * Nothing in the open tree ever names the overlay, nothing is conditional, and an open checkout
 * without one produces a working production bundle with zero configuration — because the stub is
 * real code that ships, not a placeholder for something missing.
 *
 * <h2>Why every member is a value or a synchronous lookup</h2>
 * There is deliberately no `import()` at this seam. Lazy-loading happens INSIDE the paid registry,
 * where `React.lazy` keeps each moved view in its own chunk; here an absent paid module is a
 * build-time resolution that either succeeded or did not, never a chunk 404 at 3am. The open
 * defaults are empty arrays, an empty record and three functions returning `null` — which is what
 * "this edition does not have that surface" honestly renders as.
 *
 * <h2>This is NOT a public extension interface</h2>
 * The extension interface this project promises is the CLASSIFIER one (`docs/reference/`), which is
 * backend and versioned. This is private build wiring between two halves of one repo: a commercial
 * build replaces `index.ts`, and both halves move in the same commit. Do not document it as a
 * plugin API and do not stabilise it.
 */
import type { ReactElement, ReactNode } from "react";
import type { BehaviorFindingDetail, ClassifierDebug } from "../api/types";
import type { MeOrg, OrgMember } from "../api/types-auth";
import type { NavItem } from "../shell/nav";

/**
 * The baseline audit block on one finding — an SOP-conformance-only payload (the rule was ALREADY
 * failing when its detector was fitted), which is why rendering it is a paid surface even though
 * the DTO carrying it is open. Named here so neither half of the split spells the `NonNullable<>`
 * twice.
 */
export type FindingBaseline = NonNullable<BehaviorFindingDetail["baseline"]>;

/**
 * Everything a paid build contributes to the open shell.
 *
 * Two kinds of member, and the difference is where the paid code MOUNTS. Routes and nav entries are
 * composed once at module scope, so they are plain values; the three lookups hang off a discriminator
 * the open page already has in hand (a classifier family, a detector key, a finding's baseline) and
 * are called during render, so they are functions. Both kinds answer emptily in the open edition.
 */
export type PaidSurfaces = {
  /**
   * Routes mounted at the app root, OUTSIDE `ProtectedRoute` and outside `TenantProvider`.
   *
   * `/pricing` is the only member and it is the reason this is a build-time exclusion rather than a
   * `CapabilityGate`: that route is public by requirement (K7 — a price a buyer can only see after
   * signing up is not published pricing), and `CapabilityGate` calls `useTenant()`, which THROWS
   * with no provider above it. Gating Pricing is a render-time crash on a public URL. So the open
   * edition loses the route rather than gating it, and `App.tsx` redirects `/pricing` to `/` so the
   * URL still resolves somewhere.
   */
  publicRoutes: readonly ReactElement[];

  /**
   * Routes mounted at the app root, INSIDE `ProtectedRoute` but OUTSIDE `TenantProvider` — one tier
   * in from `publicRoutes`, for a surface that needs a signed-in user but not yet a resolved
   * {orgSlug, projectSlug}. `/new-org` (#862) is the only member: creating an additional org past
   * the open build's single-org cap is a multi-org, paid-only action, and its screen is reached
   * before any org is selected, so it cannot be a project-scoped route.
   */
  protectedRoutes: readonly ReactElement[];

  /** Routes mounted inside the project-scoped `settings` route. `settings/usage` is the only member. */
  settingsRoutes: readonly ReactElement[];

  /**
   * Extra Settings rail entries, keyed by the `title` of the {@link NavItem} group they join.
   *
   * `shell/nav.tsx` is the ONE source read by both the Settings rail and the ⌘K palette, and an
   * entry there carries no capability, so a paid view removed from the open build would otherwise
   * leave a dead tab in both — offered by the palette, landing on the settings catch-all. This is
   * how the entry leaves with the view it points at.
   */
  settingsNav: Readonly<Record<string, readonly NavItem[]>>;

  /**
   * The classifier debug panel for one execution family, or `null` when this edition has none.
   *
   * Called from `views/classifiers/debug/DebugSection.tsx`'s family switch, which KEEPS its
   * `behavior_drift` arm rather than deleting it: that switch's `default` falls through to
   * `DeterministicDebug`, so a deletion would silently render the WRONG panel for a drift
   * classifier instead of rendering none. `metric_drift` is a launch default and stays open.
   *
   * Takes the whole already-fetched {@link ClassifierDebug} — an open DTO — rather than a family
   * string plus the fields one panel happens to need: the payload is in hand at the call site, and
   * threading individual paid-shaped arguments through an open type is how this seam would grow a
   * member per panel.
   */
  debugSection: (debug: ClassifierDebug) => ReactNode;

  /** The extra detail-rail block for one detector key (SOP conformance's rulebook), or `null`. */
  classifierRail: (detector: string) => ReactNode;

  /** The baseline-audit section on a finding page, or `null`. */
  findingEvidence: (baseline: FindingBaseline) => ReactNode;

  /**
   * The "Organizations" section of {@code Sidebar}'s project switcher dropdown — the header, the
   * row per org the user belongs to, and the "+ New organization" action (#862). Takes the org list
   * `GET /auth/me` already returns (no separate fetch) and the current org's slug, for the
   * highlighted-row state; renders `null` in the open edition, so the switcher shows only the
   * Projects section rather than a dead "+ New organization" button pointing at a route that
   * doesn't exist open-side.
   */
  orgSwitcherRows: (orgs: readonly MeOrg[], currentOrgSlug: string) => ReactNode;

  /**
   * The "Transfer ownership" and "Danger zone" (archive/unarchive/delete) sections of
   * `Settings/Organization.tsx` (#862) — owner-only organization lifecycle, paired with the four
   * backend routes that moved to the paid overlay's `plan/tenant/MultiOrgController`. Renders `null` in
   * the open edition, so the open Organization settings page keeps rename, members, invitations,
   * projects, and observer settings — every route that stayed on the open `OrganizationController`
   * — and loses only the four actions that did not.
   *
   * `onChanged` is the open page's own query invalidation (`["org", …]`, `["org-members", …]`), so
   * a paid archive/transfer mutation's success refreshes the SAME open-owned queries a rename does,
   * rather than this seam growing its own cache-invalidation contract.
   */
  orgLifecycleSections: (args: {
    orgSlug: string;
    archived: boolean;
    otherMembers: readonly OrgMember[];
    onChanged: () => void;
  }) => ReactNode;
};
