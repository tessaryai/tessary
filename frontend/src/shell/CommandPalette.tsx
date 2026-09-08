// SPDX-License-Identifier: Apache-2.0
/*
 * The ⌘K command palette overlay. Mounted once in the shell (CommandPaletteDock), it reads
 * open/close from PaletteContext, builds its command list from the IA (commands.tsx), and
 * navigates / runs actions via react-router. Top-anchored <dialog showModal()> so it gets the
 * platform's focus-trap, Escape handling, and backdrop for free (same primitive as ui/Modal).
 *
 * Keyboard: type to filter; ↑/↓ to move; Enter to run the highlighted command; Esc to close.
 */
import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Inbox, Search, SearchX, Waypoints, type LucideIcon } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { projectApi } from "../api/client";
import { ApiError } from "../api/types";
import { useTenant } from "../tenant/TenantContext";
import { cn } from "../ui";
import { usePalette } from "./PaletteContext";
import { useShellActions } from "./ShellActions";
import { buildCommands, filterCommands, hitIsReachable, searchHitToCommand } from "./commands";
import type { Command, CommandGroup } from "./commands";
import type { SearchHitType } from "../api/types";
import { useNavigation } from "./useNavigation";
import { useCapabilities } from "../capabilities/useCapabilities";
import { pushRecent, readRecents } from "./recents";

/** Debounce before hitting the search backend, so we issue one request per pause — not per keystroke. */
const SEARCH_DEBOUNCE_MS = 250;

type SearchState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "done"; commands: Command[] }
  | { status: "error" };

/**
 * Per-hit-type icon + semantic color. Graders read as the accent signal (text-accent), and the
 * navigational entities (cases/traces) stay muted — matching the design's "color is a signal, not
 * decoration" rule.
 */
const HIT_ICON: Record<SearchHitType, { color: string; Icon: LucideIcon }> = {
  case: { color: "text-muted", Icon: Inbox },
  trace: { color: "text-muted", Icon: Waypoints },
};

function HitIcon({ type }: { type: SearchHitType }) {
  // Defensive: the wire type is a bare string — an unknown hit type renders no icon.
  const entry = HIT_ICON[type] as { color: string; Icon: LucideIcon } | undefined;
  if (!entry) return null;
  const { color, Icon } = entry;
  return <Icon size={15} aria-hidden="true" className={cn("shrink-0", color)} />;
}

/**
 * Highlight every case-insensitive occurrence of a whitespace-split query term inside `text`,
 * wrapping matches in a bg-accent-subtle + text-accent span. Pure string work (no HTML) so it's
 * injection-safe; falls back to the plain string when the query is empty or matches nothing.
 */
function highlightTerms(text: string, query: string): ReactNode {
  const terms = query
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  if (terms.length === 0) return text;
  // Escape regex metacharacters in each term, then match any of them.
  const escaped = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  // Split keeps the captured delimiters; an even-index part is non-match, odd-index is a match.
  const parts = text.split(new RegExp(`(${escaped.join("|")})`, "gi"));
  if (parts.length <= 1) return text;
  return parts.map((part, i) =>
    i % 2 === 1 ? (
      <span key={i} className="rounded-micro bg-accent-subtle text-accent px-0.5">
        {part}
      </span>
    ) : (
      part
    ),
  );
}

export function CommandPaletteDock() {
  const { isOpen } = usePalette();
  // Mount only while open so the input autofocuses fresh and recents re-read on each open.
  if (!isOpen) return null;
  return <CommandPalette />;
}

function CommandPalette() {
  const { close } = usePalette();
  const { orgSlug, projectSlug } = useTenant();
  const shellActions = useShellActions();
  const navigate = useNavigate();
  const dlgRef = useRef<HTMLDialogElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const projectBase = `/orgs/${orgSlug}/projects/${projectSlug}/`;

  // The IA this org actually has. The palette offers exactly the sidebar's surfaces and the Settings
  // rail's sections — never a fourth, differently-filtered list (segment F4).
  const { liveNav, settingsSections, reserved, isPathReachable } = useNavigation();
  const { isEnabled } = useCapabilities();

  const commands = useMemo<Command[]>(
    () =>
      buildCommands({
        navigate,
        projectBase,
        nav: liveNav,
        settings: settingsSections,
        reserved,
        isPathReachable,
        recents: readRecents(orgSlug, projectSlug),
        openProjectSwitcher: shellActions.openProjectSwitcher,
      }),
    [
      navigate,
      projectBase,
      liveNav,
      settingsSections,
      reserved,
      isPathReachable,
      orgSlug,
      projectSlug,
      shellActions,
    ],
  );

  // Server-backed content search. Static commands stay synchronous; this async source merges
  // a pre-ranked "Results" group in on top. Surface-jump/recents are untouched — they never read this.
  const [search, setSearch] = useState<SearchState>({ status: "idle" });

  // Debounced search effect: one request per typing pause, cancelled (AbortController) when the query
  // changes or the palette unmounts — so a slow earlier response can't race a newer one. A blank query
  // resets to idle and never calls the backend.
  useEffect(() => {
    const q = query.trim();
    if (q === "") {
      setSearch({ status: "idle" });
      return;
    }
    const controller = new AbortController();
    setSearch({ status: "loading" });
    const timer = setTimeout(() => {
      projectApi(orgSlug, projectSlug)
        .search(q, controller.signal)
        .then((res) => {
          if (controller.signal.aborted) return;
          setSearch({
            status: "done",
            // Drop hits whose detail surface this org doesn't have — the index is not capability-aware,
            // so a grader indexed before the flag went off would otherwise be offered and then bounce.
            commands: res.hits
              .filter((h) => hitIsReachable(h, isEnabled))
              .map((h) => searchHitToCommand(h, navigate, projectBase)),
          });
        })
        .catch((err) => {
          // An aborted request is an expected cancellation, not an error state — ignore it.
          if (controller.signal.aborted || (err instanceof DOMException && err.name === "AbortError")) return;
          // 401 surfaces as ApiError too; treat any failure as a graceful "search unavailable".
          if (err instanceof ApiError || err instanceof Error) setSearch({ status: "error" });
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- isEnabled is rebuilt per render; the
    // capability map behind it changing re-runs this through the query key anyway.
  }, [query, orgSlug, projectSlug, navigate, projectBase]);

  // Static groups: the synchronous nav/settings/actions/recents, substring-filtered as before.
  const staticGroups = useMemo(() => filterCommands(commands, query), [commands, query]);

  // Server results are ALREADY ranked — render them as a pre-built "Results" group that bypasses the
  // substring filter, placed first. Only present once a search has resolved with at least one hit.
  const groups = useMemo<{ group: CommandGroup; items: Command[] }[]>(() => {
    const resultItems = search.status === "done" ? search.commands : [];
    const resultGroup =
      resultItems.length > 0 ? [{ group: "Results" as CommandGroup, items: resultItems }] : [];
    return [...resultGroup, ...staticGroups];
  }, [search, staticGroups]);

  // Flat list of selectable (non-disabled) commands, in render order, for ↑/↓ navigation.
  const selectable = useMemo(
    () => groups.flatMap((g) => g.items).filter((c) => !c.disabled),
    [groups],
  );

  const searchLoading = search.status === "loading";
  const searchError = search.status === "error";

  // Count of server-ranked content results — drives the footer ("N results") and the empty state.
  const resultCount = search.status === "done" ? search.commands.length : 0;
  // A true "no match" is when the user typed something but nothing — not even a static surface —
  // matched. We still offer quick jumps so the palette is never a dead end.
  const hasQuery = query.trim() !== "";
  const noMatches = hasQuery && groups.length === 0 && !searchLoading;

  // Jump straight to a top-level surface from the empty state, recording it as a recent (parity
  // with how a Navigation command would behave).
  const goTo = (id: string, label: string) => {
    const path = `${projectBase}${id}`;
    pushRecent(orgSlug, projectSlug, { path, label });
    close();
    navigate(path);
  };

  // Keep the active index in range as the filter changes.
  useEffect(() => {
    setActive((a) => (selectable.length === 0 ? 0 : Math.min(a, selectable.length - 1)));
  }, [selectable.length]);

  // Open the native modal dialog + focus the input.
  useEffect(() => {
    const dlg = dlgRef.current;
    if (dlg && !dlg.open) dlg.showModal();
    inputRef.current?.focus();
  }, []);

  // Native dialog Escape (cancel) + backdrop click → close. The keydown
  // listener consumes Escape (preventDefault) BEFORE it bubbles to window, so
  // global ESC consumers stacked underneath (the Ask rail, any open Rail) don't
  // also close on the same keypress.
  useEffect(() => {
    const dlg = dlgRef.current;
    if (!dlg) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        close();
      }
    };
    const onCancel = (e: Event) => {
      e.preventDefault();
      close();
    };
    const onClick = (e: MouseEvent) => {
      if (e.target === dlg) close();
    };
    dlg.addEventListener("keydown", onKeyDown);
    dlg.addEventListener("cancel", onCancel);
    dlg.addEventListener("click", onClick);
    return () => {
      dlg.removeEventListener("keydown", onKeyDown);
      dlg.removeEventListener("cancel", onCancel);
      dlg.removeEventListener("click", onClick);
    };
  }, [close]);

  const runCommand = (cmd: Command) => {
    if (cmd.disabled || !cmd.run) return;
    // Record navigations as recents (skip the recents entries themselves and pure actions).
    if (cmd.group === "Navigation" || cmd.group === "Settings") {
      pushRecent(orgSlug, projectSlug, { path: targetPath(cmd, projectBase), label: cmd.label });
    }
    close();
    cmd.run();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, selectable.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === "Enter") {
      e.preventDefault();
      const cmd = selectable[active];
      if (cmd) runCommand(cmd);
    }
  };

  // Scroll the active row into view as it changes.
  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-cmd-active="true"]`);
    el?.scrollIntoView({ block: "nearest" });
  }, [active]);

  return (
    <dialog
      ref={dlgRef}
      aria-label="Command palette"
      className="p-0 m-0 mx-auto mt-[12vh] w-[min(92vw,600px)] max-h-[70vh] rounded-modal bg-overlay text-fg border border-border-strong backdrop:bg-scrim backdrop:backdrop-blur-sm"
      style={{ boxShadow: "var(--shadow-md)" }}
    >
      <div className="flex flex-col max-h-[70vh]">
        <div className="flex items-center gap-2.5 px-4 py-3 border-b border-border">
          <Search size={16} aria-hidden="true" className="shrink-0 text-muted" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setActive(0);
            }}
            onKeyDown={onKeyDown}
            placeholder="Search traces, or jump to a page…"
            aria-label="Search commands"
            className="flex-1 min-w-0 bg-transparent border-none outline-none text-body text-fg placeholder:text-subtle"
          />
          <span className="font-mono text-label text-subtle border border-border-strong rounded-control px-1.5 py-0.5 shrink-0">
            ESC
          </span>
        </div>

        <div ref={listRef} className="flex-1 overflow-y-auto py-2">
          {searchLoading && (
            <div className="px-4 pt-2 pb-1 text-label uppercase text-subtle">Searching…</div>
          )}
          {searchError && (
            <div className="px-4 py-2 text-small text-muted">Search is unavailable. Showing matching pages only.</div>
          )}
          {noMatches && (
            <div className="flex flex-col items-center gap-2 px-5 py-10 text-center">
              <SearchX size={26} strokeWidth={1.4} aria-hidden="true" className="text-subtle" />
              <div className="text-body font-medium text-fg">No matches for “{query}”</div>
              <div className="text-small text-muted leading-snug">
                Try a shorter query or an ID, or jump to a page below.
              </div>
              <div className="mt-1.5 flex gap-2">
                <button
                  type="button"
                  onClick={() => goTo("triage", "Triage")}
                  className="text-small text-fg bg-raised border border-border-strong rounded-control px-2.5 py-1 hover:bg-hover transition-colors"
                  style={{ transitionDuration: "var(--duration-micro)" }}
                >
                  Go to Triage
                </button>
                <button
                  type="button"
                  onClick={() => goTo("traces", "Traces")}
                  className="text-small text-fg bg-raised border border-border-strong rounded-control px-2.5 py-1 hover:bg-hover transition-colors"
                  style={{ transitionDuration: "var(--duration-micro)" }}
                >
                  Go to Traces
                </button>
              </div>
            </div>
          )}
          {groups.map((g) => (
            <div key={g.group} className="mb-1">
              <div className="px-4 pt-2 pb-1 text-label uppercase text-subtle">{g.group}</div>
              {g.items.map((cmd) => {
                const idx = selectable.indexOf(cmd);
                const isActive = idx === active && !cmd.disabled;
                const res = cmd.result;
                return (
                  <button
                    key={cmd.id}
                    type="button"
                    data-cmd-active={isActive}
                    disabled={cmd.disabled}
                    onMouseMove={() => idx >= 0 && setActive(idx)}
                    onClick={() => runCommand(cmd)}
                    className={cn(
                      "w-full flex items-center gap-2.5 px-4 py-2 text-left transition-colors",
                      cmd.disabled
                        ? "cursor-default text-subtle"
                        : isActive
                          ? "bg-selected text-fg"
                          : "text-muted hover:text-fg hover:bg-hover",
                    )}
                    style={{ transitionDuration: "var(--duration-micro)" }}
                  >
                    {/* Server result → semantic hit-type icon; everything else → its nav icon. */}
                    {res ? (
                      <HitIcon type={res.type} />
                    ) : (
                      cmd.icon != null && (
                        <span className={cn("shrink-0", isActive ? "text-accent" : "text-subtle")}>{cmd.icon}</span>
                      )
                    )}
                    <span className="min-w-0 flex-1">
                      <span className="block text-small truncate">
                        {res ? highlightTerms(cmd.label, query) : cmd.label}
                      </span>
                      {res ? (
                        <span className="block text-label text-subtle truncate">
                          {res.typeLabel}
                          {res.snippet ? (
                            <>
                              {" · "}
                              <span className="font-mono">{res.snippet}</span>
                            </>
                          ) : null}
                        </span>
                      ) : (
                        cmd.hint && <span className="block text-label text-subtle truncate">{cmd.hint}</span>
                      )}
                    </span>
                    {/* One-release old-name alias, right-aligned and dim (sheets/ask-palette.md). */}
                    {cmd.alias && !cmd.disabled && (
                      <span className="shrink-0 text-small text-subtle">{cmd.alias}</span>
                    )}
                    {cmd.disabled && (
                      <span className="shrink-0 text-label uppercase text-subtle border border-border rounded-pill px-1.5">
                        Soon
                      </span>
                    )}
                    {/* Active result row shows the Enter affordance, before the type badge. */}
                    {res && isActive && (
                      <span className="shrink-0 font-mono text-label text-subtle" aria-hidden="true">
                        ↵
                      </span>
                    )}
                    {res && (
                      <span className="shrink-0 text-label uppercase text-muted bg-raised rounded-control px-1.5 py-0.5">
                        {res.typeLabel}
                      </span>
                    )}
                    {cmd.shortcut && !cmd.disabled && (
                      <span className="shrink-0 font-mono text-label text-muted border border-border-strong rounded-control px-1.5">
                        {cmd.shortcut}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          ))}
        </div>

        {/* Footer: result count (+ navigation/open hints). Hidden until a query is in flight, so the
            initial recents/nav view stays uncluttered. The backend returns no query time, so we show
            the count only. */}
        {hasQuery && !noMatches && (
          <div className="flex items-center gap-3.5 px-4 py-2 border-t border-border bg-surface">
            <span className="inline-flex items-center gap-1.5 text-label text-muted">
              <span className="font-mono text-label text-fg bg-raised border border-border rounded-control px-1 py-0.5">↑↓</span>
              navigate
            </span>
            <span className="inline-flex items-center gap-1.5 text-label text-muted">
              <span className="font-mono text-label text-fg bg-raised border border-border rounded-control px-1 py-0.5">↵</span>
              open
            </span>
            <span className="ml-auto text-label text-subtle">
              {searchLoading
                ? "Searching…"
                : `${resultCount} ${resultCount === 1 ? "result" : "results"}`}
            </span>
          </div>
        )}
      </div>
    </dialog>
  );
}

/** Reconstruct the route a nav/settings command targets (mirrors buildCommands). */
function targetPath(cmd: Command, projectBase: string): string {
  if (cmd.group === "Navigation") return `${projectBase}${cmd.id.replace(/^nav:/, "")}`;
  if (cmd.group === "Settings") return `${projectBase}settings/${cmd.id.replace(/^settings:/, "")}`;
  return projectBase;
}
