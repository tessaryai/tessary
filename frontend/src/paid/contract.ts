// SPDX-License-Identifier: Apache-2.0
/*
 * The seam through which another build extends this shell. Source here imports `@paid`, an alias
 * that resolves inside `frontend/src` to the stub next door (`index.ts`); a build may override
 * that one alias to supply its own implementation, and `scripts/check-open-boundary.sh` is the
 * guard that keeps this file from ever naming that implementation directly. Every member below is
 * a value or a synchronous lookup, never a lazy import, so a missing implementation is a
 * build-time resolution rather than a runtime chunk failure. This is private build wiring, not a
 * public extension interface: do not document it as a plugin API.
 */
import type { ReactElement, ReactNode } from "react";
import type { BehaviorFindingDetail, ClassifierDebug } from "../api/types";
import type { MeOrg, OrgMember } from "../api/types-auth";
import type { NavItem } from "../shell/nav";

/**
 * The baseline audit block on one finding: the rule was already failing when its detector was
 * fitted. Named here so both sides of the seam share one `NonNullable<>` instead of repeating it.
 */
export type FindingBaseline = NonNullable<BehaviorFindingDetail["baseline"]>;

/**
 * Everything this seam can add to the shell, in two kinds. Routes and nav entries are composed
 * once at module scope, so they are plain values; the three lookups hang off a discriminator the
 * page already has in hand (a classifier family, a detector key, a finding's baseline) and are
 * called during render, so they are functions. Both kinds default to empty.
 */
export type PaidSurfaces = {
  /**
   * Routes mounted at the app root, outside `ProtectedRoute` and outside `TenantProvider`.
   *
   * `/pricing` must stay public, and `CapabilityGate` calls `useTenant()`, which throws with no
   * provider above it, so gating it would crash on a public URL. This build has no route here,
   * and `App.tsx` redirects `/pricing` to `/` so the URL still resolves.
   */
  publicRoutes: readonly ReactElement[];

  /**
   * Routes mounted at the app root, inside `ProtectedRoute` but outside `TenantProvider`, one tier
   * in from `publicRoutes`, for a surface that needs a signed-in user but not yet a resolved
   * {orgSlug, projectSlug}. This build has no route here.
   */
  protectedRoutes: readonly ReactElement[];

  /** Routes mounted inside the project-scoped `settings` route. */
  settingsRoutes: readonly ReactElement[];

  /**
   * Extra Settings rail entries, keyed by the `title` of the {@link NavItem} group they join.
   *
   * `shell/nav.tsx` is the one source read by both the Settings rail and the palette, and an
   * entry there carries no capability check, so an unavailable view would otherwise leave a dead
   * tab in both. This is how the entry leaves along with the view it points at.
   */
  settingsNav: Readonly<Record<string, readonly NavItem[]>>;

  /**
   * The classifier debug panel for one execution family, or `null` when none applies.
   *
   * `views/classifiers/debug/DebugSection.tsx`'s family switch keeps its `behavior_drift` arm
   * rather than deleting it: the switch's `default` falls through to `DeterministicDebug`, so
   * deleting the arm would silently render the wrong panel instead of none.
   *
   * Takes the whole already-fetched {@link ClassifierDebug} rather than a family string plus the
   * fields one panel needs, since the payload is already in hand at the call site.
   */
  debugSection: (debug: ClassifierDebug) => ReactNode;

  /** The extra detail-rail block for one detector key, or `null`. */
  classifierRail: (detector: string) => ReactNode;

  /** The baseline-audit section on a finding page, or `null`. */
  findingEvidence: (baseline: FindingBaseline) => ReactNode;

  /**
   * The "Organizations" section of `Sidebar`'s project switcher dropdown: the header, one row per
   * org the user belongs to, and a "+ New organization" action. Takes the org list `GET /auth/me`
   * already returns and the current org's slug for the highlighted-row state. Renders `null`
   * here, so the switcher shows only the Projects section.
   */
  orgSwitcherRows: (orgs: readonly MeOrg[], currentOrgSlug: string) => ReactNode;

  /**
   * The "Transfer ownership" and "Danger zone" (archive/unarchive/delete) sections of
   * `Settings/Organization.tsx`: owner-only organization lifecycle. Renders `null` here, so this
   * settings page keeps rename, members, invitations, projects, and observer settings, and loses
   * only these actions.
   *
   * `onChanged` reuses this page's own query invalidation (`["org", …]`, `["org-members", …]`)
   * rather than this seam growing its own cache-invalidation contract.
   */
  orgLifecycleSections: (args: {
    orgSlug: string;
    archived: boolean;
    otherMembers: readonly OrgMember[];
    onChanged: () => void;
  }) => ReactNode;
};
