// SPDX-License-Identifier: Apache-2.0
/*
 * The Classifiers page's two dropdowns: the call site and tool pickers, and the Configure menu that leads to each
 * classifier's configure page.
 */
import { useState } from "react";
import { Link } from "react-router-dom";
import { ChevronDown } from "lucide-react";
import type { ClassifierMenuItem } from "../../api/types";
import { Button, Input, cn } from "../../ui";
import { useDropdown } from "../../ui/useDropdown";
import { menuStatus } from "./chartRules";

export type PickerOption = { id: string; name: string; meta: string };
export type PickerGroup = { label?: string; options: PickerOption[] };

const PANEL =
  "absolute right-0 top-full mt-1 z-30 w-80 max-h-[60vh] overflow-y-auto rounded-card bg-overlay border border-border-strong py-1";

/** A button showing the current choice, opening a list of options, with a search box when `searchLabel` is set. */
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
  const [query, setQuery] = useState("");
  const q = query.trim().toLowerCase();
  const shown = groups
    .map((g) => ({ ...g, options: g.options.filter((o) => !q || o.name.toLowerCase().includes(q)) }))
    .filter((g) => g.options.length > 0);

  const pick = (id: string) => {
    onPick(id);
    setOpen(false);
    setQuery("");
  };

  const optionList = (options: PickerOption[]) =>
    options.map((o) => (
      <button
        key={o.id}
        type="button"
        role="option"
        aria-selected={o.id === selected}
        onClick={() => pick(o.id)}
        className={cn(
          "w-full flex items-center gap-3 px-3 py-1.5 text-left transition-colors",
          o.id === selected ? "bg-selected text-fg" : "text-fg-secondary hover:bg-hover hover:text-fg",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      >
        <span className="min-w-0 flex-1 truncate font-mono text-small">{o.name}</span>
        <span className="shrink-0 text-small text-muted">{o.meta}</span>
      </button>
    ));

  return (
    <div ref={ref} className="relative">
      <Button
        size="sm"
        aria-label={label}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        trailingIcon={<ChevronDown size={14} strokeWidth={1.75} aria-hidden="true" className="text-muted" />}
      >
        <span className="font-mono">{value}</span>
      </Button>
      {open && (
        <div role="listbox" aria-label={listLabel} className={PANEL} style={{ boxShadow: "var(--shadow-md)" }}>
          {searchLabel && (
            <div className="px-2 pb-1">
              <Input
                autoFocus
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={searchLabel}
                aria-label={searchLabel}
              />
            </div>
          )}
          {shown.length === 0 && <p className="px-3 py-1.5 text-small text-muted">No match.</p>}
          {shown.map((g, i) =>
            g.label ? (
              <div key={g.label} role="group" aria-label={g.label}>
                <div className="px-3 pt-1.5 pb-1 text-label uppercase text-subtle">{g.label}</div>
                {optionList(g.options)}
              </div>
            ) : (
              <div key={i}>{optionList(g.options)}</div>
            ),
          )}
        </div>
      )}
    </div>
  );
}

/** "Configure classifiers": every classifier with where it runs, each leading to its configure page. */
export function ConfigureMenu({ classifiers, basePath }: { classifiers: ClassifierMenuItem[]; basePath: string }) {
  const { open, setOpen, ref } = useDropdown();
  return (
    <div ref={ref} className="relative">
      <Button
        size="sm"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
        trailingIcon={<ChevronDown size={14} strokeWidth={1.75} aria-hidden="true" className="text-muted" />}
      >
        Configure classifiers
      </Button>
      {open && (
        <div role="menu" aria-label="Configure a classifier" className={PANEL} style={{ boxShadow: "var(--shadow-md)" }}>
          {classifiers.length === 0 && <p className="px-3 py-1.5 text-small text-muted">No classifiers yet.</p>}
          {classifiers.map((c) => (
            <Link
              key={c.id}
              role="menuitem"
              to={`${basePath}/classifiers/${encodeURIComponent(c.id)}`}
              onClick={() => setOpen(false)}
              className="flex items-center gap-3 px-3 py-1.5 text-fg hover:bg-hover transition-colors"
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
