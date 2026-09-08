// SPDX-License-Identifier: Apache-2.0
/*
 * The global navigation shell: IA source of truth + the chrome that wraps every
 * project surface. App.tsx consumes ShellChrome; surface authors who need to know the IA
 * import from `./nav`.
 */
export { ShellChrome } from "./ShellChrome";
export {
  NAV,
  NAV_GROUPS,
  TRIAGE_NAV,
  LIVE_NAV,
  RESERVED_NAV,
  SETTINGS_GROUPS,
  SETTINGS_SECTIONS,
} from "./nav";
export type { NavItem, NavGroup, SettingsGroup, SettingsSection, SurfaceState } from "./nav";
// The capability-filtered view of that IA — what a given org actually has. Prefer this over the raw
// lists above for anything a user sees (segment F).
export { useNavigation } from "./useNavigation";
export { PaletteProvider, usePalette } from "./PaletteContext";
export { useDropdown } from "./useDropdown";
