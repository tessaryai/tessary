// SPDX-License-Identifier: Apache-2.0
// The palette's commands, all derived from the capability-filtered IA the caller passes in
// `ctx.nav` / `ctx.settings`, never from nav.tsx directly.
import type { ReactNode } from "react";
import type { SearchHit, SearchHitType } from "../api/types";
import { SETTINGS_ICON } from "./nav";
import type { NavItem, SettingsSection } from "./nav";
import type { RecentEntry } from "./recents";

export type CommandGroup = "Results" | "Recent" | "Navigation" | "Settings" | "Actions" | "Coming soon";

export type Command = {
  id: string;
  label: string;
  group: CommandGroup;
  icon?: ReactNode;
  hint?: string;
  keywords?: string[];
  alias?: string;
  disabled?: boolean;
  run?: () => void;
  /** Server "Results" commands only. */
  result?: {
    type: SearchHitType;
    typeLabel: string;
    rawId: string;
    snippet: string | null;
  };
};

export type CommandContext = {
  navigate: (path: string) => void;
  projectBase: string;
  nav: NavItem[];
  settings: SettingsSection[];
  reserved: NavItem[];
  isPathReachable: (path: string) => boolean;
  recents: RecentEntry[];
  openProjectSwitcher: () => void;
};

function navIcon(Icon: NavItem["icon"]): ReactNode {
  return <Icon size={15} aria-hidden="true" />;
}

/** Group order is the renderer's, not this list's. */
export function buildCommands(ctx: CommandContext): Command[] {
  const cmds: Command[] = [];

  // Recents are localStorage and outlive a capability going off, so unreachable ones are dropped.
  for (const r of ctx.recents.filter((r) => ctx.isPathReachable(r.path))) {
    cmds.push({
      id: `recent:${r.path}`,
      label: r.label,
      group: "Recent",
      hint: "Recently visited",
      run: () => ctx.navigate(r.path),
    });
  }

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

  cmds.push(
    {
      id: "action:switch-project",
      label: "Switch organization or project…",
      group: "Actions",
      keywords: ["org", "organization", "tenant", "change project"],
      run: ctx.openProjectSwitcher,
    },
  );

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

/** The one home for the entity-to-route mapping; routes mirror App.tsx. */
const HIT_ROUTING: Record<SearchHitType, { typeLabel: string; path: (id: string) => string }> = {
  trace: { typeLabel: "Trace", path: (id) => `traces/${id}` },
};

export function searchHitToCommand(
  hit: SearchHit,
  navigate: (path: string) => void,
  projectBase: string,
): Command {
  const hitType = hit.type as SearchHitType;
  const route = HIT_ROUTING[hitType];
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
