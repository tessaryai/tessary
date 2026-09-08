// SPDX-License-Identifier: Apache-2.0
/*
 * ShellChrome — the persistent frame around every project surface: the sidebar, the main
 * content well, and the command palette (⌘K). Providers are layered here (not at the app
 * root) because they are project-scoped.
 */
import { useEffect } from "react";
import type { ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Info } from "lucide-react";
import { useTenant } from "../tenant/TenantContext";
import { Sidebar } from "./Sidebar";
import { PaletteProvider } from "./PaletteContext";
import { CommandPaletteDock } from "./CommandPalette";
import { ShellActionsProvider } from "./ShellActions";
import { LIVE_NAV } from "./nav";
import { pushRecent } from "./recents";

export function ShellChrome({
  children,
  isSample = false,
}: {
  children: ReactNode;
  /** #1227: renders the persistent "Sample data" banner above the content column
   *  (never the sidebar) on every screen inside the sample project. Computed by the caller
   *  (App.tsx's ProjectShell) rather than here, since it already runs the `listProjects` read this
   *  needs and there is no reason for ShellChrome to issue a second one. */
  isSample?: boolean;
}) {
  return (
    <ShellActionsProvider>
      <PaletteProvider>
        <RecentRouteTracker />
        <div className="relative flex h-screen overflow-hidden bg-bg text-fg">
          <SkipLink />
          <Sidebar />
          <div className="flex-1 min-w-0 flex flex-col overflow-hidden">
            {isSample && <SampleBanner />}
            <main id="main-content" tabIndex={-1} className="flex-1 min-w-0 overflow-y-auto outline-none">
              {/* Above the content well, not inside a surface: a platform-wide grading stop applies to
                  every surface, so it must not scroll away with one view's content. */}
              {children}
            </main>
          </div>
          <CommandPaletteDock />
        </div>
      </PaletteProvider>
    </ShellActionsProvider>
  );
}

/**
 * The sample project's persistent banner (design-spec.md, `SampleProject.dc.html`): spans the
 * content column only, never the sidebar, and never scrolls away since it sits above `<main>`
 * rather than inside it. One primary action -- no delete button here, project deletion already
 * works generically via Settings' existing project-delete surface.
 */
function SampleBanner() {
  const { orgSlug } = useTenant();
  const nav = useNavigate();
  return (
    <div className="flex items-center gap-4 px-7 py-3.5 bg-raised border-b border-border-strong flex-none">
      <Info size={16} strokeWidth={2} className="text-link flex-none" aria-hidden="true" />
      <div className="flex-1 text-body text-fg">Sample data</div>
      <button
        type="button"
        onClick={() => nav(`/orgs/${orgSlug}`)}
        className="flex-none inline-flex items-center text-small font-medium text-accent-text-on bg-accent rounded-control px-3.5 py-2 hover:bg-accent-hover transition-colors"
      >
        Connect your own traces
      </button>
    </div>
  );
}

/**
 * Keyboard skip-link (WCAG 2.4.1). Visually hidden until focused — the first
 * Tab stop on every page — letting keyboard and screen-reader users jump past the
 * sidebar straight to the main content well.
 */
function SkipLink() {
  return (
    <a
      href="#main-content"
      className="sr-only focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:z-[100] focus:rounded-control focus:bg-accent focus:px-3 focus:py-1.5 focus:text-small focus:text-accent-text-on">
      Skip to content
    </a>
  );
}

/** Records visits to top-level surfaces so the palette's "Recent" group reflects real usage. */
function RecentRouteTracker() {
  const { orgSlug, projectSlug } = useTenant();
  const location = useLocation();

  useEffect(() => {
    const base = `/orgs/${orgSlug}/projects/${projectSlug}/`;
    if (!location.pathname.startsWith(base)) return;
    const segment = location.pathname.slice(base.length).split("/")[0];
    if (!segment) return;
    const surface = LIVE_NAV.find((n) => n.id === segment);
    if (surface) pushRecent(orgSlug, projectSlug, { path: `${base}${surface.id}`, label: surface.label });
  }, [location.pathname, orgSlug, projectSlug]);

  return null;
}
