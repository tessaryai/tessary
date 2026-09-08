// SPDX-License-Identifier: Apache-2.0
/*
 * The global navigation sidebar — 2026-07 redesign shell (sheets/shell.md).
 *
 * Fixed 240px, dark only. Top → bottom: brand row (the Space Grotesk wordmark,
 * DESIGN_SYSTEM.md § Typography) · Search (⌘K, same index as the palette) · Triage
 * with the app's ONLY nav badge (open cases, red) · the
 * Monitor / Calibrate groups · project switcher + Settings · account row
 * (no theme toggle — dark only).
 *
 * The IA is derived from nav.tsx (single source of truth), filtered by the org's
 * capability object through useNavigation: a surface the org doesn't have is
 * ABSENT, and a group emptied by that filtering disappears with its heading
 * (segment F1). A partner therefore sees Triage + Monitor and no Calibrate band
 * at all, rather than three padlocked rows advertising something unbuyable.
 */
import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Search } from "lucide-react";
import { auth as authApi } from "../api/client";
import { paid } from "@paid";
import { useTenant } from "../tenant/TenantContext";
import { useAuth } from "../auth/AuthContext";
import { cn } from "../ui";
import { SETTINGS_ICON } from "./nav";
import type { NavItem } from "./nav";
import { useNavigation } from "./useNavigation";
import { useDropdown } from "./useDropdown";
import { useShellActions } from "./ShellActions";
import { usePalette } from "./PaletteContext";
import { useCaseCounts } from "./useCases";
import { useSidebarCollapsed } from "./useSidebarCollapsed";

export function Sidebar() {
  const { open: openCases } = useCaseCounts();
  const { collapsed, toggle } = useSidebarCollapsed();
  const { triage, groups } = useNavigation();

  const renderItem = (item: NavItem, badge?: number) => (
    <NavLinkRow key={item.id} item={item} badge={badge} collapsed={collapsed} />
  );

  return (
    <aside
      className="relative shrink-0 bg-surface border-r border-border flex flex-col h-full"
      style={{
        width: collapsed ? "var(--sidebar-width-collapsed)" : "var(--sidebar-width-expanded)",
        transition: "width var(--duration-transition) var(--ease-enter)" }}
    >
      <BrandRow collapsed={collapsed} />
      <div className={cn("pb-3", collapsed ? "px-2" : "px-2.5")}>
        <SearchButton collapsed={collapsed} />
      </div>
      <nav className="flex-1 overflow-y-auto px-2.5 pb-2 flex flex-col gap-0.5">
        {renderItem(triage, openCases)}
        {groups.map((group) => (
          <div key={group.label} role="group" aria-label={group.label} className="flex flex-col gap-0.5">
            {collapsed ? (
              <div className="my-2 mx-1 border-t border-border" aria-hidden="true" />
            ) : (
              <div className="font-mono text-label uppercase text-subtle px-2.5 pt-3.5 pb-1">
                {group.label}
              </div>
            )}
            {group.items.map((item) => renderItem(item))}
          </div>
        ))}
      </nav>
      <div className="px-2.5 pt-1.5 pb-2 flex flex-col gap-0.5 border-t border-border">
        <ProjectSwitcher collapsed={collapsed} />
        <NavLinkRow
          item={{ id: "settings", label: "Settings", icon: SETTINGS_ICON }}
          collapsed={collapsed}
        />
      </div>
      <AccountRow collapsed={collapsed} />
      <CollapseHandle collapsed={collapsed} onClick={toggle} />
    </aside>
  );
}

/** The always-visible edge handle that flips `collapsed` — anchored to the sidebar's own border, not squeezed into the 64px icon column. */
function CollapseHandle({ collapsed, onClick }: { collapsed: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
      title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
      className="absolute top-1/2 -right-2.5 z-10 flex items-center justify-center size-5 rounded-pill border border-border-strong bg-surface text-subtle hover:text-fg hover:border-accent transition-colors"
      style={{ transform: "translateY(-50%)", transitionDuration: "var(--duration-micro)" }}
    >
      {collapsed ? <ChevronRight size={10} aria-hidden="true" /> : <ChevronLeft size={10} aria-hidden="true" />}
    </button>
  );
}

function BrandRow({ collapsed }: { collapsed: boolean }) {
  const { orgSlug, projectSlug } = useTenant();
  const nav = useNavigate();
  return (
    <div className={cn("flex items-center pt-5 pb-3", collapsed ? "justify-center px-2" : "px-4")}>
      <button
        type="button"
        onClick={() => nav(`/orgs/${orgSlug}/projects/${projectSlug}/triage`)}
        aria-label="Go to Triage"
        title={collapsed ? "Tessary" : undefined}
        className="flex items-center gap-2 min-w-0 rounded-control hover:opacity-80 transition-opacity"
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <img src="/tessary-logo.png" alt="Tessary" className="size-7 shrink-0 rounded-control" />
        {!collapsed && (
          // The wordmark is the one place in the app that is not token-driven: Space Grotesk 700
          // and a literal #FFFFFF are a fixed brand spec (DESIGN_SYSTEM.md), deliberately outside
          // the type scale and the grey ramp. Do not "fix" these to tokens.
          <span
            className="shrink-0"
            style={{
              fontFamily: "'Space Grotesk Variable', system-ui, sans-serif",
              fontWeight: 700,
              fontSize: "20px",
              letterSpacing: "-0.02em",
              lineHeight: 1.2,
              color: "#FFFFFF",
              textDecoration: "none" }}
          >
            tessary
          </span>
        )}
      </button>
    </div>
  );
}

function SearchButton({ collapsed }: { collapsed: boolean }) {
  const palette = usePalette();
  return (
    <button
      type="button"
      onClick={() => palette.open()}
      title={collapsed ? "Search (⌘K)" : undefined}
      className={cn(
        "w-full flex items-center rounded-control bg-bg border border-border text-small text-muted hover:text-fg hover:border-border-strong transition-colors cursor-pointer",
        collapsed ? "justify-center p-1.5" : "gap-2.5 px-2.5 py-1.5",
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <Search size={14} aria-hidden="true" className="shrink-0" />
      {!collapsed && (
        <>
          <span className="flex-1 text-left">Search…</span>
          <span className="font-mono text-label border border-border-strong rounded-control px-1.5">⌘K</span>
        </>
      )}
    </button>
  );
}

function ProjectSwitcher({ collapsed }: { collapsed: boolean }) {
  const { orgSlug, projectSlug } = useTenant();
  const nav = useNavigate();
  const { open, setOpen, ref } = useDropdown();
  const { registerProjectSwitcher } = useShellActions();

  useEffect(() => {
    registerProjectSwitcher(() => setOpen(true));
  }, [registerProjectSwitcher, setOpen]);

  // #862: GET /api/me/orgs moved to the paid overlay -- the org list for the switcher's display name
  // comes off GET /auth/me (already fetched by AuthProvider) instead of a second query.
  const { user } = useAuth();
  const orgs = user?.orgs ?? [];
  const projects = useQuery({
    queryKey: ["projects", orgSlug],
    queryFn: () => authApi.listProjects(orgSlug),
    enabled: !!orgSlug,
  });

  const currentOrg = orgs.find((o) => o.slug === orgSlug);
  const currentProject = projects.data?.find((p) => p.slug === projectSlug);

  /*
    #862: the multi-org section of this dropdown -- the "Organizations" header, one row per org, and
    "+ New organization" -- is a paid surface. The open stub renders null, so this switcher shows
    only Projects: no dead action pointing at a route the open build doesn't build (the R1 risk this
    issue's grounding flagged -- the switcher, not NewOrg.tsx alone, is the actual multi-org UI).
    Held in a variable rather than called inline because the section divider below has to know
    whether there is a section above it at all.
  */
  const orgRows = paid.orgSwitcherRows(orgs, orgSlug);

  const go = (to: string) => {
    setOpen(false);
    nav(to);
  };

  return (
    <div
      ref={ref}
      className="relative"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        onClick={() => setOpen(true)}
        onFocus={() => setOpen(true)}
        aria-expanded={open}
        aria-haspopup="menu"
        title={collapsed ? `${currentOrg?.name ?? orgSlug} / ${currentProject?.name ?? projectSlug}` : undefined}
        className={cn(
          "w-full flex items-center rounded-control hover:bg-hover transition-colors",
          collapsed ? "justify-center px-1.5 py-1.5" : "gap-2 px-2.5 py-1.5 text-left",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        {collapsed ? (
          <span
            className="inline-flex items-center justify-center size-6 rounded-control bg-raised text-fg text-label shrink-0"
            aria-hidden="true"
          >
            {(currentProject?.name ?? projectSlug).charAt(0).toUpperCase()}
          </span>
        ) : (
          <>
            <span className="min-w-0 flex-1">
              <span
                className="block text-small text-fg truncate"
                title={`${currentOrg?.name ?? orgSlug} / ${currentProject?.name ?? projectSlug}`}
              >
                {currentProject?.name ?? projectSlug}
              </span>
              <span className="block text-small text-muted truncate">{currentOrg?.name ?? orgSlug}</span>
            </span>
            <ChevronRight size={12} aria-hidden="true" className="shrink-0 text-subtle" />
          </>
        )}
      </button>

      {open && (
        // Flyout opens to the side, into the page (flush at the sidebar's edge so the
        // pointer can cross into it without leaving the hover area and dismissing it).
        <div
          className="absolute left-full bottom-0 w-60 max-h-[70vh] overflow-y-auto rounded-card bg-overlay border border-border-strong py-1 z-50"
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          {orgRows}

          {/* Separates the two sections, so it only exists when there ARE two: the open build's
              `orgSwitcherRows` returns null, and an unconditional rule left a divider hanging above
              the Projects header with nothing above it to divide. */}
          {orgRows != null && <div className="my-1 border-t border-border" />}

          <div className="px-3 pt-1.5 pb-1 text-label uppercase text-subtle">Projects</div>
          {(projects.data ?? []).map((p) => (
            <SwitcherRow
              key={p.id}
              name={p.name}
              selected={p.slug === projectSlug}
              onClick={() =>
                p.slug === projectSlug ? setOpen(false) : go(`/orgs/${orgSlug}/projects/${p.slug}/triage`)
              }
            />
          ))}
          <SwitcherAction label="+ New project" onClick={() => go(`/orgs/${orgSlug}/new-project`)} />
        </div>
      )}
    </div>
  );
}

/** Exported for the paid `orgSwitcherRows` seam (#862), which reuses this row exactly. */
export function SwitcherRow({
  name,
  selected,
  onClick,
}: {
  name: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "w-full flex items-center gap-2 px-3 py-1.5 text-left transition-colors",
        selected ? "bg-selected text-fg" : "text-muted hover:text-fg hover:bg-hover",
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <span className="min-w-0 flex-1 text-small truncate">{name}</span>
      {selected && <Check size={12} aria-hidden="true" className="shrink-0 text-accent" />}
    </button>
  );
}

/** Exported for the paid `orgSwitcherRows` seam (#862), which reuses this row exactly. */
export function SwitcherAction({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="w-full text-left px-3 py-1.5 text-small text-accent hover:bg-hover transition-colors"
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      {label}
    </button>
  );
}

/**
 * One sidebar row. There is no locked variant: `useNavigation` has already dropped every surface the
 * org cannot reach, so anything rendered here is navigable.
 */
function NavLinkRow({
  item,
  badge,
  collapsed,
}: {
  item: NavItem;
  /** Triage only: the open-case count. The app's ONLY nav count. */
  badge?: number;
  collapsed?: boolean;
}) {
  const { orgSlug, projectSlug } = useTenant();
  const nav = useNavigate();
  const location = useLocation();
  const projectBase = `/orgs/${orgSlug}/projects/${projectSlug}/`;
  const href = `${projectBase}${item.id}`;
  const active =
    location.pathname === href ||
    location.pathname.startsWith(href + "/") ||
    (item.match?.some((m) => location.pathname.startsWith(projectBase + m)) ?? false);
  const hasBadge = badge != null && badge > 0;

  return (
    <button
      type="button"
      onClick={() => nav(href)}
      title={collapsed ? item.label : undefined}
      className={cn(
        "relative w-full flex items-center rounded-control text-small transition-colors",
        collapsed ? "justify-center px-0 py-2" : "gap-2.5 text-left px-2.5 py-1.5",
        active ? "bg-selected text-fg" : "text-muted hover:text-fg hover:bg-hover",
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      {active && (
        <span aria-hidden="true" className="absolute left-0 top-1.5 bottom-1.5 w-0.5 rounded-pill bg-accent" />
      )}
      <span className="relative shrink-0 inline-flex">
        <item.icon size={15} strokeWidth={1.75} aria-hidden="true" className={cn(active && "text-accent")} />
        {hasBadge && collapsed && (
          <span aria-hidden="true" className="absolute -top-0.5 -right-0.5 size-1.5 rounded-pill bg-error" />
        )}
      </span>
      <span className={cn("flex-1 truncate", collapsed && "sr-only")}>{item.label}</span>
      {hasBadge && !collapsed && (
        <span
          className="font-mono text-label font-semibold text-error bg-error-subtle rounded-pill leading-[1.6] py-0 px-1.75"
          
          aria-label={`${badge} open cases`}
        >
          {badge}
        </span>
      )}
    </button>
  );
}

function AccountRow({ collapsed }: { collapsed: boolean }) {
  const { user } = useAuth();
  const { open, setOpen, ref } = useDropdown();
  const onSignOut = async () => {
    try {
      const { frontendUrl } = await authApi.logout();
      window.location.assign(frontendUrl);
    } catch {
      window.location.assign("/");
    }
  };

  if (!user) return null;
  const initial = (user.email ?? "?").charAt(0).toUpperCase();
  return (
    <div ref={ref} className="px-2 py-2 border-t border-border relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        title={collapsed ? user.email : undefined}
        className={cn(
          "w-full min-w-0 flex items-center rounded-control text-small text-muted hover:text-fg hover:bg-hover transition-colors",
          collapsed ? "justify-center px-1 py-1.5" : "gap-2 px-2 py-1.5",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <span
          className="inline-flex items-center justify-center size-6 rounded-pill bg-raised text-fg text-label shrink-0"
          aria-hidden="true"
        >
          {initial}
        </span>
        {!collapsed && (
          <span className="truncate text-left flex-1" title={user.email}>
            {user.email}
          </span>
        )}
      </button>
      {open && (
        // Fixed width, anchored at the left edge — `right-2` would pin it to the
        // trigger's own width, which breaks once that trigger is a 64px icon column.
        <div
          className="absolute bottom-full left-2 w-48 mb-1 rounded-card bg-overlay border border-border-strong py-1 z-50"
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          <button
            type="button"
            onClick={onSignOut}
            className="w-full text-left px-3 py-1.5 text-small text-fg hover:bg-hover">
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
