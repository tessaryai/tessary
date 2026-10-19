// SPDX-License-Identifier: Apache-2.0
import { useEffect, useId, useRef } from "react";
import { EllipsisVertical, Plus } from "lucide-react";
import { cn, IconButton } from "../../ui";
import { closeOnFocusOut, itemsOf } from "../../ui/menuKeys";
import { useDropdown } from "../../ui/useDropdown";
import type { MetricDashboardSummary } from "./wire";

const PILL =
  "h-8 px-3.5 inline-flex items-center gap-1.5 rounded-pill border text-small transition-colors " +
  "disabled:opacity-50 disabled:cursor-not-allowed";
const IDLE = "border-border text-muted hover:bg-hover hover:text-fg";
const ITEM =
  "flex w-full items-center h-8 px-2.5 rounded-control text-left text-small text-fg-secondary " +
  "hover:bg-hover hover:text-fg focus:bg-hover focus:text-fg outline-none";

/** The kebab acts on the current dashboard; the built-in one can only be duplicated, and Rename waits for its read. */
function DashboardMenu({
  dashboard,
  disabled,
  onRename,
  onDuplicate,
  onDelete,
}: {
  dashboard: MetricDashboardSummary;
  disabled: boolean;
  onRename: ((d: MetricDashboardSummary) => void) | undefined;
  onDuplicate: (d: MetricDashboardSummary) => void;
  onDelete: (d: MetricDashboardSummary) => void;
}) {
  const { open, setOpen, ref } = useDropdown();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const actions = [
    ...(dashboard.built_in || !onRename ? [] : [{ label: "Rename", run: onRename }]),
    { label: "Duplicate", run: onDuplicate },
    ...(dashboard.built_in ? [] : [{ label: "Delete", run: onDelete }]),
  ];

  useEffect(() => {
    if (open) itemsOf(menuRef.current, "menuitem")[0].focus();
  }, [open]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current!.focus();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const items = itemsOf(menuRef.current, "menuitem");
    const at = items.indexOf(e.target as HTMLElement);
    let to: HTMLElement | undefined;
    if (e.key === "Escape") close(true);
    else if (e.key === "ArrowDown") to = items[(at + 1) % items.length];
    else if (e.key === "ArrowUp") to = items[(at - 1 + items.length) % items.length];
    else if (e.key === "Home") to = items[0];
    else if (e.key === "End") to = items[items.length - 1];
    else return;
    e.preventDefault();
    to?.focus();
  };

  return (
    <div ref={ref} className="relative ml-auto" onBlur={closeOnFocusOut(ref, () => close(false))}>
      <IconButton
        ref={triggerRef}
        size="sm"
        label="Dashboard actions"
        disabled={disabled}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        className="disabled:opacity-50 disabled:cursor-not-allowed"
        onClick={() => (open ? close(false) : setOpen(true))}
      >
        <EllipsisVertical size={16} strokeWidth={1.75} aria-hidden="true" />
      </IconButton>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label="Dashboard actions"
          tabIndex={-1}
          className="absolute right-0 top-full mt-1 w-[188px] rounded-card bg-overlay border border-border p-1 z-50 outline-none"
          style={{ boxShadow: "var(--shadow-md)" }}
          onKeyDown={onKeyDown}
        >
          {actions.map((a) => (
            <button
              key={a.label}
              type="button"
              role="menuitem"
              tabIndex={-1}
              className={ITEM}
              onClick={() => {
                close(true);
                a.run(dashboard);
              }}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function DashboardSwitcher({
  dashboards,
  currentId,
  canManage,
  disabled,
  onSelect,
  onNew,
  onRename,
  onDuplicate,
  onDelete,
}: {
  dashboards: MetricDashboardSummary[];
  currentId: string;
  canManage: boolean;
  /** True in edit mode: switching away would drop the draft. */
  disabled: boolean;
  onSelect: (id: string) => void;
  onNew: () => void;
  /** Absent until the current dashboard has loaded. */
  onRename?: (d: MetricDashboardSummary) => void;
  onDuplicate: (d: MetricDashboardSummary) => void;
  onDelete: (d: MetricDashboardSummary) => void;
}) {
  const current = dashboards.find((d) => d.id === currentId);
  return (
    <nav aria-label="Dashboards" className="mb-4 flex items-center gap-2 flex-wrap">
      {dashboards.map((d) => {
        const here = d.id === currentId;
        return (
          <button
            key={d.id}
            type="button"
            aria-current={here ? "page" : undefined}
            disabled={disabled && !here}
            onClick={() => onSelect(d.id)}
            className={cn(PILL, here ? "bg-raised border-accent-edge text-fg" : IDLE)}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {d.name}
          </button>
        );
      })}
      {canManage && (
        <button
          type="button"
          disabled={disabled}
          onClick={onNew}
          className={cn(PILL, IDLE, "border-dashed")}
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          <Plus size={14} strokeWidth={1.75} aria-hidden="true" />
          New dashboard
        </button>
      )}
      {canManage && current && (
        <DashboardMenu
          dashboard={current}
          disabled={disabled}
          onRename={onRename}
          onDuplicate={onDuplicate}
          onDelete={onDelete}
        />
      )}
    </nav>
  );
}
