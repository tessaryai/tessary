// SPDX-License-Identifier: Apache-2.0
/*
 * The IA the CURRENT ORG actually has — nav.tsx filtered by the capability object (segment F1/F2/F4).
 *
 * One derivation, three readers: the sidebar, the Settings rail, and the ⌘K palette. A surface the org
 * doesn't have is absent from all three, and a group left with nothing in it disappears rather than
 * rendering an empty heading — which is what a partner's "Calibrate" band would otherwise be, since
 * Graders, Observer and Review are all off at launch.
 *
 * DROPPED, not locked. The sidebar used to render a padlocked row with "Not enabled for this
 * organization"; with entitlements collapsed into the flag layer there is nothing to buy, so a locked row
 * was an advertisement for a thing that cannot be obtained. F's goal is that a partner's session
 * contains no visible mention of a gated feature at all.
 *
 * Fail-closed while the capability read is in flight (see {@link useCapabilities}): a gated surface
 * stays hidden rather than appearing and then vanishing. `CapabilityService.require` on each gated
 * endpoint is the authority; this is the UI half.
 */
import { useMemo } from "react";
import { useCapabilities } from "../capabilities/useCapabilities";
import type { CapabilityWire } from "../api/types-auth";
import {
  NAV_GROUPS,
  RESERVED_NAV,
  SETTINGS_GROUPS,
  TRIAGE_NAV,
  type NavGroup,
  type NavItem,
  type SettingsGroup,
  type SettingsSection,
} from "./nav";

/** Anything the IA gates: a nav item or a settings section, both keyed by an optional capability. */
type Gated = { capability?: CapabilityWire };

/** Keep the entries whose capability is on (or absent). */
function allowed<T extends Gated>(items: T[], isEnabled: (c: CapabilityWire) => boolean): T[] {
  return items.filter((i) => i.capability == null || isEnabled(i.capability));
}

export interface Navigation {
  /** Triage — ungated, always present; the redirect target for anything that isn't. */
  triage: NavItem;
  /** The grouped IA below Triage, with gated items removed and empty groups dropped. */
  groups: NavGroup[];
  /** Every live surface the org has, flat — Triage first. The palette's Navigation group. */
  liveNav: NavItem[];
  /** Settings rail groups, same filtering. */
  settingsGroups: SettingsGroup[];
  /** Every settings section the org has, flat — the palette's Settings group. */
  settingsSections: SettingsSection[];
  /** Roadmap slots. Never gated: "coming soon" is a statement about us, not about this org. */
  reserved: NavItem[];
  /** Whether a surface id (a route segment under the project base) is reachable by this org. */
  isReachable: (id: string) => boolean;
  /**
   * Whether an absolute in-app path still leads somewhere this org can go. Anything that isn't a
   * known gated surface passes — a case or trace deep link is not the IA's business.
   */
  isPathReachable: (path: string) => boolean;
}

export function useNavigation(): Navigation {
  const { capabilities } = useCapabilities();

  return useMemo(() => {
    // Fail-closed, matching useCapabilities: an unresolved map answers false for everything.
    const isEnabled = (c: CapabilityWire) => capabilities?.[c] === true;
    const groups = NAV_GROUPS.map((g) => ({ ...g, items: allowed(g.items, isEnabled) })).filter(
      (g) => g.items.length > 0,
    );
    const liveNav = [TRIAGE_NAV, ...groups.flatMap((g) => g.items)];
    const settingsGroups = SETTINGS_GROUPS.map((g) => ({
      ...g,
      items: allowed(g.items, isEnabled),
    })).filter((g) => g.items.length > 0);
    const settingsSections = settingsGroups.flatMap((g) => g.items);
    const reachable = new Set(liveNav.map((n) => n.id));
    // Every id the IA gates, reachable or not — a path is only rejected if its surface is one we
    // KNOW is gated off, never merely because it isn't a top-level surface.
    const gated = new Set(
      [...NAV_GROUPS.flatMap((g) => g.items), ...SETTINGS_GROUPS.flatMap((g) => g.items)]
        .filter((i) => i.capability != null)
        .map((i) => i.id),
    );
    const reachableSettings = new Set(settingsSections.map((s) => s.id));
    return {
      triage: TRIAGE_NAV,
      groups,
      liveNav,
      settingsGroups,
      settingsSections,
      reserved: RESERVED_NAV,
      isReachable: (id: string) => reachable.has(id),
      isPathReachable: (path: string) => {
        const parts = path.split("/").filter(Boolean);
        // /orgs/:org/projects/:project/<surface>[/<settings-section>]
        const surface = parts[4];
        if (surface == null) return true;
        if (surface === "settings") {
          const section = parts[5];
          return section == null || !gated.has(section) || reachableSettings.has(section);
        }
        return !gated.has(surface) || reachable.has(surface);
      },
    };
    // Keyed on the resolved map itself, not on a hand-listed set of capability names: adding a gated
    // surface must not also require remembering to add its flag to a dependency array. React Query
    // hands back a stable reference until the read actually changes.
  }, [capabilities]);
}
