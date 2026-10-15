// SPDX-License-Identifier: Apache-2.0
// nav.tsx filtered by the org's capabilities, for the sidebar, Settings rail and palette. Gated
// surfaces are dropped, not locked, and fail closed while the read is in flight.
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

type Gated = { capability?: CapabilityWire };

function allowed<T extends Gated>(items: T[], isEnabled: (c: CapabilityWire) => boolean): T[] {
  return items.filter((i) => i.capability == null || isEnabled(i.capability));
}

export interface Navigation {
  triage: NavItem;
  groups: NavGroup[];
  liveNav: NavItem[];
  settingsGroups: SettingsGroup[];
  settingsSections: SettingsSection[];
  /** Never gated: "coming soon" is a statement about us, not this org. */
  reserved: NavItem[];
  /** Anything that is not a known gated surface passes; a deep link is not the IA's business. */
  isPathReachable: (path: string) => boolean;
}

export function useNavigation(): Navigation {
  const { capabilities } = useCapabilities();

  return useMemo(() => {
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
      isPathReachable: (path: string) => {
        const parts = path.split("/").filter(Boolean);
        const surface = parts[4];
        if (surface == null) return true;
        if (surface === "settings") {
          const section = parts[5];
          return section == null || !gated.has(section) || reachableSettings.has(section);
        }
        return !gated.has(surface) || reachable.has(surface);
      },
    };
    // Keyed on the resolved map so a new gated surface needs no dependency-array edit.
  }, [capabilities]);
}
