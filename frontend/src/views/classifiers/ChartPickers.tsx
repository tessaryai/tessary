// SPDX-License-Identifier: Apache-2.0
/*
 * The Classifiers page's two dropdowns: the call site and tool pickers, and the Configure menu that leads to each
 * classifier's configure page.
 *
 * Both work from the keyboard as their roles promise: the arrows move focus through the options or items, Escape
 * and a pick give focus back to the button that opened them, and moving focus out of one closes it.
 */
import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronDown } from "lucide-react";
import type { ClassifierMenuItem } from "../../api/types";
import { Button, ErrorNote, Input, LoadingRow, cn } from "../../ui";
import { useDropdown } from "../../ui/useDropdown";
import { menuStatus } from "./chartRules";

export type PickerOption = { id: string; name: string; meta: string };
export type PickerGroup = { label?: string; options: PickerOption[] };

const PANEL =
  "absolute right-0 top-full mt-1 z-30 w-80 max-h-[60vh] overflow-y-auto rounded-card bg-overlay border border-border-strong py-1";

/** The elements of `panel` carrying `role`, in document order. */
function itemsOf(panel: HTMLElement | null, role: string): HTMLElement[] {
  return panel ? Array.from(panel.querySelectorAll<HTMLElement>(`[role="${role}"]`)) : [];
}

/**
 * Closes the dropdown when focus moves to something outside it. Focus that goes nowhere (a click on a part of the page
 * that takes no focus) is left to the dropdown's outside-click handling, so a click on an option that does not take
 * focus still lands.
 */
function closeOnFocusOut(container: React.RefObject<HTMLElement | null>, close: () => void) {
  return (e: React.FocusEvent) => {
    const next = e.relatedTarget as Node | null;
    if (next && container.current && !container.current.contains(next)) close();
  };
}

/**
 * A button showing the current choice, opening a list of options, with a search box above the list when
 * `searchLabel` is set. The button's name is the label and the choice ("Call site: kb_answer.generate").
 */
export function ScopePicker({
  label,
  listLabel,
  value,
  groups,
  selected,
  onPick,
  searchLabel,
}: {
  label: string;
  listLabel: string;
  value: string;
  groups: PickerGroup[];
  selected: string | null;
  onPick: (id: string) => void;
  searchLabel?: string;
}) {
  const { open, setOpen, ref } = useDropdown();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const shown = groups
    .map((g) => ({ ...g, options: g.options.filter((o) => !q || o.name.toLowerCase().includes(q)) }))
    .filter((g) => g.options.length > 0);

  // With no search box to type in, focus starts on the current choice.
  useEffect(() => {
    if (!open || searchLabel) return;
    const options = itemsOf(panelRef.current, "option");
    (options.find((o) => o.getAttribute("aria-selected") === "true") ?? options[0])?.focus();
  }, [open, searchLabel]);

  const close = (refocus: boolean) => {
    setOpen(false);
    setQuery("");
    if (refocus) triggerRef.current?.focus();
  };

  const pick = (id: string) => {
    onPick(id);
    close(true);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const options = itemsOf(panelRef.current, "option");
    const inSearch = e.target instanceof HTMLInputElement;
    const at = options.indexOf(e.target as HTMLElement);
    let to: HTMLElement | undefined;
    if (e.key === "Escape") {
      close(true);
    } else if (e.key === "ArrowDown") {
      to = inSearch ? options[0] : options[Math.min(options.length - 1, at + 1)];
    } else if (e.key === "ArrowUp") {
      to = at <= 0 && searchLabel ? (panelRef.current?.querySelector("input") ?? undefined) : options[Math.max(0, at - 1)];
    } else if (e.key === "Home" && !inSearch) {
      to = options[0];
    } else if (e.key === "End" && !inSearch) {
      to = options[options.length - 1];
    } else if (e.key === "Enter" && inSearch && shown.length > 0) {
      pick(shown[0].options[0].id);
    } else {
      return;
    }
    e.preventDefault();
    to?.focus();
  };

  const optionList = (options: PickerOption[]) =>
    options.map((o) => (
      <button
        key={o.id}
        type="button"
        role="option"
        tabIndex={-1}
        aria-selected={o.id === selected}
        onClick={() => pick(o.id)}
        className={cn(
          "w-full flex items-center gap-3 px-3 py-1.5 text-left transition-colors focus:bg-hover focus:text-fg",
          o.id === selected ? "bg-selected text-fg" : "text-fg-secondary hover:bg-hover hover:text-fg",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <span className="min-w-0 flex-1 truncate font-mono text-small">{o.name}</span>
        <span className="shrink-0 text-small text-muted">{o.meta}</span>
      </button>
    ));

  return (
    <div ref={ref} className="relative" onBlur={closeOnFocusOut(ref, () => close(false))}>
      <Button
        ref={triggerRef}
        size="sm"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        onClick={() => (open ? close(false) : setOpen(true))}
        trailingIcon={<ChevronDown size={14} strokeWidth={1.75} aria-hidden="true" className="text-muted" />}
      >
        <span className="sr-only">{`${label}:`}</span> <span className="font-mono">{value}</span>
      </Button>
      {open && (
        <div ref={panelRef} className={PANEL} style={{ boxShadow: "var(--shadow-md)" }} onKeyDown={onKeyDown}>
          {searchLabel && (
            <div className="px-2 pb-1">
              <Input
                autoFocus
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={searchLabel}
                aria-label={searchLabel}
                aria-controls={listId}
              />
            </div>
          )}
          {shown.length === 0 && <p className="px-3 py-1.5 text-small text-muted">No match.</p>}
          <div id={listId} role="listbox" aria-label={listLabel}>
            {shown.map((g, i) =>
              g.label ? (
                <div key={g.label} role="group" aria-label={g.label}>
                  <div aria-hidden="true" className="px-3 pt-1.5 pb-1 text-label uppercase text-muted">
                    {g.label}
                  </div>
                  {optionList(g.options)}
                </div>
              ) : (
                <div key={i} role="presentation">
                  {optionList(g.options)}
                </div>
              ),
            )}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * "Configure classifiers": every classifier with where it runs, each leading to its configure page. `classifiers` is
 * undefined until the read lands, so the menu says it is loading or why it failed, never that there are none.
 */
export function ConfigureMenu({
  classifiers,
  error,
  basePath,
}: {
  classifiers: ClassifierMenuItem[] | undefined;
  error: unknown;
  basePath: string;
}) {
  const { open, setOpen, ref } = useDropdown();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();

  // Focus goes into the menu as it opens: onto the first classifier, or onto the menu while they load.
  useEffect(() => {
    if (!open) return;
    (itemsOf(menuRef.current, "menuitem")[0] ?? menuRef.current)?.focus();
  }, [open]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const items = itemsOf(menuRef.current, "menuitem");
    const at = items.indexOf(e.target as HTMLElement);
    let to: HTMLElement | undefined;
    if (e.key === "Escape") close(true);
    else if (e.key === "ArrowDown") to = items[(at + 1) % items.length];
    else if (e.key === "ArrowUp") to = items[at <= 0 ? items.length - 1 : at - 1];
    else if (e.key === "Home") to = items[0];
    else if (e.key === "End") to = items[items.length - 1];
    else return;
    e.preventDefault();
    to?.focus();
  };

  return (
    <div ref={ref} className="relative" onBlur={closeOnFocusOut(ref, () => close(false))}>
      <Button
        ref={triggerRef}
        size="sm"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => (open ? close(false) : setOpen(true))}
        trailingIcon={<ChevronDown size={14} strokeWidth={1.75} aria-hidden="true" className="text-muted" />}
      >
        Configure classifiers
      </Button>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label="Configure a classifier"
          tabIndex={-1}
          className={cn(PANEL, "outline-none")}
          style={{ boxShadow: "var(--shadow-md)" }}
          onKeyDown={onKeyDown}
        >
          {classifiers === undefined && (
            <div className="px-3 py-1.5">{error ? <ErrorNote error={error} /> : <LoadingRow />}</div>
          )}
          {classifiers?.length === 0 && <p className="px-3 py-1.5 text-small text-muted">No classifiers yet.</p>}
          {classifiers?.map((c) => (
            <Link
              key={c.id}
              role="menuitem"
              tabIndex={-1}
              to={`${basePath}/classifiers/${encodeURIComponent(c.id)}`}
              onClick={() => setOpen(false)}
              className="flex items-center gap-3 px-3 py-1.5 text-fg hover:bg-hover focus:bg-hover transition-colors"
              style={{ transitionDuration: "var(--duration-micro)" }}
            >
              <span className="min-w-0 flex-1 truncate text-small">{c.name}</span>
              <span className={cn("shrink-0 text-small", c.status === "off" ? "text-subtle" : "text-muted")}>
                {menuStatus(c)}
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
