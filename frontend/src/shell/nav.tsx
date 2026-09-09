// SPDX-License-Identifier: Apache-2.0
/*
 * The product's information architecture: the single source of truth for the
 * sidebar and the command palette.
 *
 * Navigation is organized around usage modes, not data types:
 *
 *   Triage            ← above the groups; the front door; badge = open cases only
 *   MONITOR             Traces · Classifiers · Vitals
 *   ─────────────
 *   Ask Tessary ⌘J      (sidebar footer)
 *   Settings · project switcher · account
 *
 * Old surface names live on here as palette keywords so a bookmark or habit
 * still finds the current one.
 *
 *   - `live` surfaces render in the sidebar AND the palette, and own a <Route>.
 *   - `reserved` surfaces are roadmap slots: palette-only "Coming soon", no route.
 *
 * Icons are lucide-react components: `icon` holds the component itself, not
 * a rendered element, so callers can size/style it consistently at each call site.
 */
import {
  Activity,
  Bell,
  Hourglass,
  Boxes,
  Building2,
  Database,
  Filter,
  FileUp,
  FlaskConical,
  GitBranch,
  Inbox,
  KeyRound,
  Plug,
  Route as RouteIcon,
  Settings as SettingsIcon,
  Shield,
  TrendingUp,
  Users,
  Zap,
  type LucideIcon,
} from "lucide-react";
import type { CapabilityWire } from "../api/types-auth";
// This build's settings-nav extension point; empty here, see src/paid/index.ts.
import { paid } from "@paid";

export type SurfaceState = "live" | "reserved";

/** One navigable area of the product. Sidebar + palette both derive from these. */
export type NavItem = {
  /** Route segment under the project base, and the item's stable key. */
  id: string;
  label: string;
  /** Sidebar/palette icon: a lucide-react component, instantiated at each call site. */
  icon: LucideIcon;
  /** Short description shown as the palette result subtitle. */
  description?: string;
  /** Extra terms the palette matches on, including each surface's old name as an alias. */
  keywords?: string[];
  /**
   * Rendered old-name alias, shown right-aligned and dim in the palette for ONE release
   * (sheets/ask-palette.md), e.g. `was "Pipeline"`. Distinct from `keywords`, which only match.
   */
  alias?: string;
  /** Routes that should also light this item up in the sidebar (e.g. detail pages). */
  match?: string[];
  /** "live" (default) renders in the sidebar; "reserved" is a roadmap-only palette slot. */
  state?: SurfaceState;
  /**
   * The capability that has to be on for this surface to exist at all. Absent means ungated.
   *
   * It lives here rather than in a side map because the IA is what gets derived from it: the
   * sidebar, the ⌘K palette and the route table all read the same filtered list, so a surface can
   * never be offered in one of the three and be a dead end in the other two.
   */
  capability?: CapabilityWire;
};

/** A labeled sidebar group (the mono-caps `Monitor` / `Calibrate` headings). */
export type NavGroup = { label: string; items: NavItem[] };

/** Triage, above the groups; the only nav item that ever carries a badge. */
export const TRIAGE_NAV: NavItem = {
  id: "triage",
  label: "Triage",
  description: "Open cases, worst first.",
  keywords: ["cases", "inbox", "home", "overview", "worklist", "triage"],
  alias: 'was "Overview"',
  match: ["cases"],
  icon: Inbox,
};

/** The grouped IA below Triage. Order is the sidebar order. */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: "Monitor",
    items: [
      {
        id: "traces",
        label: "Traces",
        description: "Every trace, span, and conversation Tessary has received.",
        keywords: ["explore", "telemetry", "spans", "otel", "conversation", "session", "logs"],
        alias: 'was "Explore"',
        // An execution path, not a waveform: Vitals already owns the pulse
        // silhouette, and a route reads as "the path the agent took."
        icon: RouteIcon,
      },
      {
        id: "classifiers",
        label: "Classifiers",
        description: "The checks watching production traffic, and the findings they create.",
        keywords: [
          "signals",
          "detections",
          "findings",
          "drift",
          "behavior drift",
          "behavior",
          "baseline",
          "monitoring",
        ],
        // en-US per constitution rule 5 (the prototype printed "Behaviour").
        alias: 'absorbed "Behavior drift"',
        // Sorting/detecting traces against a criterion: a filter, not a guard.
        icon: Filter,
      },
      {
        id: "vitals",
        label: "Vitals",
        description: "Spend and latency per call site. Counted, not judged.",
        keywords: ["spend", "cost", "latency", "p95", "tool errors", "pulse", "usage"],
        icon: Activity,
      },
    ],
  },
];

// --- Reserved roadmap surfaces. Palette-only "Coming soon"; no route, no sidebar slot. ---
const RESERVED: NavItem[] = [
  {
    id: "experiments",
    label: "Experiments",
    state: "reserved",
    description: "A/B prompt and model experiments.",
    keywords: ["ab", "compare", "trials"],
    icon: FlaskConical,
  },
  {
    id: "predictions",
    label: "Predictions",
    state: "reserved",
    description: "Predicted quality impact of code changes.",
    keywords: ["forecast", "impact", "regressions", "changes"],
    icon: TrendingUp,
  },
  {
    id: "alerts",
    label: "Notifications",
    state: "reserved",
    description: "Notifications on regressions and drift.",
    keywords: ["alerts", "warnings", "monitors"],
    icon: Bell,
  },
];

/** Flat IA: Triage, then every grouped surface, then reserved slots. */
export const NAV: NavItem[] = [TRIAGE_NAV, ...NAV_GROUPS.flatMap((g) => g.items), ...RESERVED];

/** The surfaces that own a route and render in the sidebar. */
export const LIVE_NAV: NavItem[] = NAV.filter((n) => (n.state ?? "live") === "live");

/** Roadmap surfaces shown only in the palette as non-navigable "Coming soon" hints. */
export const RESERVED_NAV: NavItem[] = NAV.filter((n) => n.state === "reserved");

export const SETTINGS_ICON: LucideIcon = SettingsIcon;

/** One Settings sub-section: a routed tab under `settings/`. */
export type SettingsSection = {
  /** Route segment under `settings/`, and the section's stable key. */
  id: string;
  label: string;
  icon: LucideIcon;
  /** The capability that has to be on for this section to exist. Absent means ungated. */
  capability?: CapabilityWire;
};

/** A titled band of the Settings rail. */
export type SettingsGroup = { title: string; items: SettingsSection[] };

/**
 * Settings sub-sections: second-level IA, reachable in ≤2 clicks via Settings → tab.
 * Administration only: anything that tunes a living concept lives with the concept
 * (Signal tuning → Classifiers).
 *
 * One source, read by both the Settings rail and the ⌘K palette, so the two can never drift
 * apart and offer a destination in one but not the other.
 */
export const SETTINGS_GROUPS: SettingsGroup[] = [
  {
    title: "Organization",
    items: [
      { id: "organization", label: "Organization", icon: Building2 },
      { id: "members", label: "Members", icon: Users },
      // Extra sections this build can register; empty here, see src/paid/index.ts.
      ...(paid.settingsNav.Organization ?? []),
    ],
  },
  {
    title: "Data & ingestion",
    items: [
      { id: "sources", label: "Sources", icon: Database },
      // A `.tessary/` bundle is the pipeline the plugin publishes: call sites, chains, failure
      // modes. Ungated.
      { id: "import", label: "Import bundle", icon: FileUp },
      // The keys an org brings: every lane runs on the org's own credential, so an org without
      // this page cannot triage a finding at all. Still capability-gated, because an org can be
      // targeted off; the default is on (see Capability.BYO_PROVIDER_KEYS).
      { id: "providers", label: "Providers", icon: Zap, capability: "byo_provider_keys_enabled" },
      // Next to Providers on purpose: that page is which keys the org has stored, this one is
      // which model each job spends them on.
      { id: "models", label: "Models", icon: Boxes },
      // The repo Observer and RCA require, and that upgrades Layer-2 triage from ruling on
      // trace evidence to ruling against the committed spec. Ungated: a repo is an optional
      // upgrade for every org, never a setup step.
      { id: "git", label: "Git integration", icon: GitBranch },
    ],
  },
  {
    title: "Alerting",
    items: [
      // How a case reaches a human when nobody is watching the app: the destination, the cadence,
      // and the quiet hours. Gated on the capability that owns the whole surface, so an org
      // without alerting sees the section disappear rather than a 403.
      { id: "notifications", label: "Notifications", icon: Bell, capability: "alerts_enabled" },
    ],
  },
  {
    title: "Security & access",
    items: [
      // Plug (not KeyRound) so it's distinct from API keys right below it in the same list.
      { id: "mcp-tokens", label: "MCP tokens", icon: Plug, capability: "api_access_enabled" },
      { id: "api-keys", label: "API keys", icon: KeyRound, capability: "api_access_enabled" },
      // Deliberately ungated even though `custom_redaction_enabled` is off by default: this page
      // also states the built-in defaults, which is exactly what a partner's security review
      // reads. The capability gates authoring a custom rule, inside the page, not the page itself.
      { id: "pii-redaction", label: "PII redaction", icon: Shield },
      // How long this project keeps traces and detections; the install default comes from the
      // deployment's environment, and this page is the per-project override.
      { id: "retention", label: "Data retention", icon: Hourglass },
    ],
  },
];

/** Every settings section, flat and in rail order. */
export const SETTINGS_SECTIONS: SettingsSection[] = SETTINGS_GROUPS.flatMap((g) => g.items);
