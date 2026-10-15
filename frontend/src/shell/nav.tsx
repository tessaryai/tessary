// SPDX-License-Identifier: Apache-2.0
// The IA: the single source for the sidebar and the palette. Old surface names live on as palette
// keywords so a bookmark or habit still finds the current one.
import {
  Activity,
  Bell,
  Hourglass,
  Boxes,
  Building2,
  Database,
  Filter,
  FileUp,
  GitBranch,
  Inbox,
  KeyRound,
  Plug,
  Route as RouteIcon,
  Settings as SettingsIcon,
  Shield,
  ToggleRight,
  Users,
  Zap,
  type LucideIcon,
} from "lucide-react";
import type { CapabilityWire } from "../api/types-auth";

export type SurfaceState = "live" | "reserved";

export type NavItem = {
  id: string;
  label: string;
  icon: LucideIcon;
  description?: string;
  keywords?: string[];
  /** A route named here wins over another item whose own route it sits under. */
  match?: string[];
  /** Here, not in a side map, so the sidebar, palette and route table read one filtered list. */
  capability?: CapabilityWire;
};

export type NavGroup = { label: string; items: NavItem[] };

export const TRIAGE_NAV: NavItem = {
  id: "triage",
  label: "Triage",
  description: "Open cases, worst first, and the findings that are not a case yet.",
  keywords: ["cases", "findings", "inbox", "home", "overview", "worklist", "triage"],
  match: ["cases", "classifiers/findings"],
  icon: Inbox,
};

export const NAV_GROUPS: NavGroup[] = [
  {
    label: "Monitor",
    items: [
      {
        id: "traces",
        label: "Traces",
        description: "Every trace, span, and conversation Tessary has received.",
        keywords: ["explore", "telemetry", "spans", "otel", "conversation", "session", "logs"],
        icon: RouteIcon,
      },
      {
        id: "classifiers",
        label: "Classifiers",
        description: "What each classifier detects, charted per call site and per tool.",
        keywords: [
          "signals",
          "detections",
          "charts",
          "configure",
          "drift",
          "behavior drift",
          "behavior",
          "baseline",
          "monitoring",
        ],
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

export const LIVE_NAV: NavItem[] = [TRIAGE_NAV, ...NAV_GROUPS.flatMap((g) => g.items)];

export const SETTINGS_ICON: LucideIcon = SettingsIcon;

export type SettingsSection = {
  id: string;
  label: string;
  icon: LucideIcon;
  capability?: CapabilityWire;
};

export type SettingsGroup = { title: string; items: SettingsSection[] };

/** Administration only: anything that tunes a living concept lives with the concept. */
export const SETTINGS_GROUPS: SettingsGroup[] = [
  {
    title: "Organization",
    items: [
      { id: "organization", label: "Organization", icon: Building2 },
      { id: "members", label: "Members", icon: Users },
      { id: "features", label: "Features", icon: ToggleRight },
    ],
  },
  {
    title: "Data & ingestion",
    items: [
      { id: "sources", label: "Sources", icon: Database },
      { id: "import", label: "Import bundle", icon: FileUp },
      // Every lane runs on the org's own credential, so without this page an org cannot triage at all.
      { id: "providers", label: "Providers", icon: Zap, capability: "byo_provider_keys_enabled" },
      { id: "models", label: "Models", icon: Boxes },
      { id: "git", label: "Git integration", icon: GitBranch },
    ],
  },
  {
    title: "Alerting",
    items: [
      { id: "notifications", label: "Notifications", icon: Bell, capability: "alerts_enabled" },
    ],
  },
  {
    title: "Security & access",
    items: [
      { id: "mcp-tokens", label: "MCP tokens", icon: Plug, capability: "api_access_enabled" },
      { id: "api-keys", label: "API keys", icon: KeyRound, capability: "api_access_enabled" },
      // Ungated: it states the built-in defaults a security review reads; the capability gates authoring.
      { id: "pii-redaction", label: "PII redaction", icon: Shield },
      { id: "retention", label: "Data retention", icon: Hourglass },
    ],
  },
];
