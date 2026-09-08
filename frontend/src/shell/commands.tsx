// SPDX-License-Identifier: Apache-2.0
/*
 * The command palette's command model and its (client-side) sources.
 *
 * A `Command` is anything the palette can list: a jump-to-surface, a context action
 * (switch project/env, open the agent), or a recent item. The palette derives ALL commands
 * from one place here so the IA in nav.tsx stays the single source of truth.
 *
 * Static commands (nav/settings/actions/recents) are built CLIENT-SIDE by `buildCommands`.
 * Server-backed full-text search over CONTENT (issues/traces) comes in through a
 * deliberate async seam: `searchHitToCommand` maps a backend `SearchHit` into a `Command` in the
 * "Results" group, and the palette merges those into the same list it renders — the static command
 * sources and the filter below are untouched.
 *
 * The palette offers nothing the org cannot reach (segment F4). The caller passes the CAPABILITY-
 * FILTERED IA in `ctx.nav` / `ctx.settings` rather than reading nav.tsx directly, and a search hit
 * whose entity type belongs to a gated surface is dropped by `searchHitToCommand`.
 */
import type { ReactNode } from "react";
import type { SearchHit, SearchHitType } from "../api/types";
import { SETTINGS_ICON } from "./nav";
import type { NavItem, SettingsSection } from "./nav";
import type { CapabilityWire } from "../api/types-auth";
import type { RecentEntry } from "./recents";

export type CommandGroup = "Results" | "Recent" | "Navigation" | "Settings" | "Actions" | "Coming soon";

export type Command = {
  id: string;
  label: string;
  group: CommandGroup;
  icon?: ReactNode;
  /** Result subtitle. */
  hint?: string;
  /** Extra terms to match on. */
  keywords?: string[];
  /** Right-aligned dim old-name alias (one release only), e.g. `was "Pipeline"`. */
  alias?: string;
  /** Right-aligned shortcut hint (e.g. "⌘J"). */
  shortcut?: string;
  /** Reserved/coming-soon commands are listed but not actionable. */
  disabled?: boolean;
  /** What running the command does. Omitted for disabled commands. */
  run?: () => void;
  /**
   * Present only on server "Results" commands — the raw search hit metadata the palette
   * needs to render the elevated result row (hit-type icon + color, type badge, matched-term
   * highlighting, and the "TypeLabel · snippet" hint with the id in mono). Static commands
   * (nav/settings/actions/recents) leave this undefined and render the classic row.
   */
  result?: {
    type: SearchHitType;
    typeLabel: string;
    /** Raw entity id, rendered in mono in the hint line. */
    rawId: string;
    snippet: string | null;
  };
};

/** Inputs the palette has at open time, used to build the live command list. */
export type CommandContext = {
  /** Navigate (react-router) to a tenant-scoped path. */
  navigate: (path: string) => void;
  /** Absolute project base, e.g. `/orgs/acme/projects/web/`. */
  projectBase: string;
  /** The live surfaces this org HAS — already capability-filtered by `useNavigation`. */
  nav: NavItem[];
  /** The settings sections this org HAS — same filtering. */
  settings: SettingsSection[];
  /** Roadmap slots ("Coming soon"). Never gated. */
  reserved: NavItem[];
  /** Whether a project-relative path is still reachable — see `useNavigation().isPathReachable`. */
  isPathReachable: (path: string) => boolean;
  recents: RecentEntry[];
  openProjectSwitcher: () => void;
};

function navIcon(Icon: NavItem["icon"]): ReactNode {
  return <Icon size={15} aria-hidden="true" />;
}

/**
 * Build the full client-side command list, newest-relevant first within each group.
 * Groups are ordered Recent → Navigation → Settings → Actions → Coming soon by the
 * palette's renderer, not here.
 */
export function buildCommands(ctx: CommandContext): Command[] {
  const cmds: Command[] = [];

  // Recent — local-only navigation aid. Recents are localStorage, so they outlive a flag going off:
  // a "Graders" entry from before the flip would otherwise be offered and bounce straight to Triage.
  for (const r of ctx.recents.filter((r) => ctx.isPathReachable(r.path))) {
    cmds.push({
      id: `recent:${r.path}`,
      label: r.label,
      group: "Recent",
      hint: "Recently visited",
      run: () => ctx.navigate(r.path),
    });
  }

  // Navigation — every live surface the org has (sidebar parity, including the capability filter).
  // ≤2 clicks: one keystroke + Enter.
  for (const n of ctx.nav) {
    cmds.push({
      id: `nav:${n.id}`,
      label: n.label,
      group: "Navigation",
      icon: navIcon(n.icon),
      hint: n.description,
      keywords: n.keywords,
      alias: n.alias,
      run: () => ctx.navigate(`${ctx.projectBase}${n.id}`),
    });
  }

  // Settings sub-sections — second-level IA, made reachable in one palette hit.
  for (const s of ctx.settings) {
    cmds.push({
      id: `settings:${s.id}`,
      label: `Settings · ${s.label}`,
      group: "Settings",
      icon: navIcon(SETTINGS_ICON),
      keywords: ["settings", s.label.toLowerCase()],
      run: () => ctx.navigate(`${ctx.projectBase}settings/${s.id}`),
    });
  }

  // Actions — context switches. These save clicks vs. the sidebar dropdowns.
  cmds.push(
    {
      id: "action:switch-project",
      label: "Switch organization or project…",
      group: "Actions",
      keywords: ["org", "organization", "tenant", "change project"],
      run: ctx.openProjectSwitcher,
    },
  );

  // Coming soon — roadmap surfaces, discoverable but not navigable (no dead-end clicks).
  for (const r of ctx.reserved) {
    cmds.push({
      id: `soon:${r.id}`,
      label: r.label,
      group: "Coming soon",
      icon: navIcon(r.icon),
      hint: r.description,
      keywords: r.keywords,
      disabled: true,
    });
  }

  return cmds;
}

/**
 * Per-hit-type display label + the detail route (relative to the project base) a result navigates to.
 * This is the SINGLE home for the entity→route mapping (the backend emits only the `type` discriminant),
 * so adding a searchable entity is a one-line change here, never a cross-file switch that can rot when the
 * IA shifts. Routes mirror App.tsx. The ⌘K contract limits the index to surfaces, cases and trace ids.
 */
const HIT_ROUTING: Record<
  SearchHitType,
  { typeLabel: string; path: (id: string) => string; capability?: CapabilityWire }
> = {
  case: { typeLabel: "Case", path: (id) => `cases/${id}` },
  trace: { typeLabel: "Trace", path: (id) => `traces/${id}` },
};

/**
 * Whether a search hit's detail route is reachable by this org (segment F4). The index is not
 * capability-aware, so the palette drops a hit rather than offering a row that redirects straight
 * back to Triage.
 */
export function hitIsReachable(hit: SearchHit, isEnabled: (c: CapabilityWire) => boolean): boolean {
  const capability = HIT_ROUTING[hit.type as SearchHitType]?.capability;
  return capability == null || isEnabled(capability);
}

/**
 * Map one server search hit to a palette `Command`. The hint line carries the entity type (and snippet
 * when present) so a user can tell a grader hit from a trace hit at a glance. `navigate` + `projectBase`
 * are the same inputs `buildCommands` uses, so a result jumps to the right tenant-scoped detail route.
 */
export function searchHitToCommand(
  hit: SearchHit,
  navigate: (path: string) => void,
  projectBase: string,
): Command {
  const hitType = hit.type as SearchHitType;
  // Defensive: the wire type is a bare string, so an unknown hit type degrades to Triage.
  const route = HIT_ROUTING[hitType] ?? { typeLabel: hit.type, path: () => "triage" };
  const hint = hit.snippet ? `${route.typeLabel} · ${hit.snippet}` : route.typeLabel;
  return {
    id: `result:${hit.type}:${hit.id}`,
    label: hit.title,
    group: "Results",
    hint,
    result: {
      type: hitType,
      typeLabel: route.typeLabel,
      rawId: hit.id,
      snippet: hit.snippet,
    },
    run: () => navigate(`${projectBase}${route.path(hit.id)}`),
  };
}

const GROUP_ORDER: CommandGroup[] = ["Results", "Recent", "Navigation", "Settings", "Actions", "Coming soon"];

/**
 * Filter by a query (case-insensitive substring over label + hint + keywords) and group,
 * preserving GROUP_ORDER. An empty query returns everything. Linear over a small static
 * list (~25 commands) — no memoization or virtualization needed.
 */
export function filterCommands(cmds: Command[], query: string): { group: CommandGroup; items: Command[] }[] {
  const q = query.trim().toLowerCase();
  const matched = q
    ? cmds.filter((c) => {
        const hay = [c.label, c.hint ?? "", ...(c.keywords ?? [])].join(" ").toLowerCase();
        return hay.includes(q);
      })
    : cmds;

  return GROUP_ORDER.map((group) => ({
    group,
    items: matched.filter((c) => c.group === group),
  })).filter((g) => g.items.length > 0);
}
