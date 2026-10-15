// SPDX-License-Identifier: Apache-2.0
// The ⌘K palette: a top-anchored <dialog showModal()> for the platform's focus trap, Escape and backdrop.
import { useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Search, SearchX, Waypoints, type LucideIcon } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { projectApi } from "../api/client";
import { ApiError } from "../api/types";
import { useTenant } from "../tenant/TenantContext";
import { cn } from "../ui";
import { usePalette } from "./PaletteContext";
import { useShellActions } from "./ShellActions";
import { buildCommands, filterCommands, searchHitToCommand } from "./commands";
import type { Command, CommandGroup } from "./commands";
import type { SearchHitType } from "../api/types";
import { useNavigation } from "./useNavigation";
import { pushRecent, readRecents } from "./recents";

const SEARCH_DEBOUNCE_MS = 250;

type SearchState =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "done"; commands: Command[] }
  | { status: "error" };

const HIT_ICON: Record<SearchHitType, { color: string; Icon: LucideIcon }> = {
  trace: { color: "text-muted", Icon: Waypoints },
};

function HitIcon({ type }: { type: SearchHitType }) {
  const { color, Icon } = HIT_ICON[type];
  return <Icon size={15} aria-hidden="true" className={cn("shrink-0", color)} />;
}

function highlightTerms(text: string, query: string): ReactNode {
  const terms = query
    .trim()
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  if (terms.length === 0) return text;
  const escaped = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
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

  // The palette offers exactly the sidebar's surfaces and the Settings rail's sections.
  const { liveNav, settingsSections, isPathReachable } = useNavigation();

  const commands = useMemo<Command[]>(
    () =>
      buildCommands({
        navigate,
        projectBase,
        nav: liveNav,
        settings: settingsSections,
        isPathReachable,
        recents: readRecents(orgSlug, projectSlug),
        openProjectSwitcher: shellActions.openProjectSwitcher,
      }),
    [
      navigate,
      projectBase,
      liveNav,
      settingsSections,
      isPathReachable,
      orgSlug,
      projectSlug,
      shellActions,
    ],
  );

  const [search, setSearch] = useState<SearchState>({ status: "idle" });

  // Aborted when the query changes or the palette unmounts, so a slow response cannot race a newer one.
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
            commands: res.hits.map((h) => searchHitToCommand(h, navigate, projectBase)),
          });
        })
        .catch((err) => {
          if (controller.signal.aborted || (err instanceof DOMException && err.name === "AbortError")) return;
          if (err instanceof ApiError || err instanceof Error) setSearch({ status: "error" });
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, orgSlug, projectSlug, navigate, projectBase]);

  const staticGroups = useMemo(() => filterCommands(commands, query), [commands, query]);

  // Server results are already ranked, so they bypass the substring filter.
  const groups = useMemo<{ group: CommandGroup; items: Command[] }[]>(() => {
    const resultItems = search.status === "done" ? search.commands : [];
    const resultGroup =
      resultItems.length > 0 ? [{ group: "Results" as CommandGroup, items: resultItems }] : [];
    return [...resultGroup, ...staticGroups];
  }, [search, staticGroups]);

  const selectable = useMemo(() => groups.flatMap((g) => g.items), [groups]);

  const searchLoading = search.status === "loading";
  const searchError = search.status === "error";

  const resultCount = search.status === "done" ? search.commands.length : 0;
  const hasQuery = query.trim() !== "";
  const noMatches = hasQuery && groups.length === 0 && !searchLoading;

  const goTo = (id: string, label: string) => {
    const path = `${projectBase}${id}`;
    pushRecent(orgSlug, projectSlug, { path, label });
    close();
    navigate(path);
  };

  useEffect(() => {
    setActive((a) => (selectable.length === 0 ? 0 : Math.min(a, selectable.length - 1)));
  }, [selectable.length]);

  useEffect(() => {
    const dlg = dlgRef.current;
    if (dlg && !dlg.open) dlg.showModal();
    inputRef.current?.focus();
  }, []);

  // Consumes Escape before it bubbles to window, so ESC consumers stacked underneath do not also close.
  useEffect(() => {
    const dlg = dlgRef.current!;
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
                const isActive = idx === active;
                const res = cmd.result;
                return (
                  <button
                    key={cmd.id}
                    type="button"
                    data-cmd-active={isActive}
                    onMouseMove={() => setActive(idx)}
                    onClick={() => runCommand(cmd)}
                    className={cn(
                      "w-full flex items-center gap-2.5 px-4 py-2 text-left transition-colors",
                      isActive ? "bg-selected text-fg" : "text-muted hover:text-fg hover:bg-hover",
                    )}
                    style={{ transitionDuration: "var(--duration-micro)" }}
                  >
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
                  </button>
                );
              })}
            </div>
          ))}
        </div>

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

function targetPath(cmd: Command, projectBase: string): string {
  if (cmd.group === "Navigation") return `${projectBase}${cmd.id.replace(/^nav:/, "")}`;
  return `${projectBase}settings/${cmd.id.replace(/^settings:/, "")}`;
}
