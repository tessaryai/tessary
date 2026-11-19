// SPDX-License-Identifier: Apache-2.0
import { useEffect, useId, useRef, type ReactNode } from "react";
import { Ellipsis } from "lucide-react";
import { IconButton } from "./Button";
import { cn } from "./cn";
import { closeOnFocusOut, itemsOf } from "./menuKeys";
import { useDropdown } from "./useDropdown";

export type ActionMenuItem = {
  label: string;
  onSelect: () => void;
  icon?: ReactNode;
  /** A destructive action: its icon takes the error hue, its label stays grey. */
  danger?: boolean;
  disabled?: boolean;
};

const ITEM =
  "flex w-full items-center gap-2 h-8 px-2.5 rounded-control text-left text-small text-fg-secondary " +
  "hover:bg-hover hover:text-fg focus:bg-hover focus:text-fg outline-none disabled:text-subtle disabled:cursor-not-allowed";

/**
 * The "More actions" button: an icon button that opens a menu of actions. Arrow keys, Home and End move between the
 * enabled items, Escape closes and returns focus to the button.
 */
export function ActionMenu({ label, items, className }: { label: string; items: ActionMenuItem[]; className?: string }) {
  const { open, setOpen, ref } = useDropdown();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();

  const enabledItems = () => itemsOf(menuRef.current, "menuitem").filter((el) => !(el as HTMLButtonElement).disabled);

  useEffect(() => {
    if (open) enabledItems()[0]?.focus();
  }, [open]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const enabled = enabledItems();
    const at = enabled.indexOf(e.target as HTMLElement);
    let to: HTMLElement | undefined;
    if (e.key === "Escape") close(true);
    else if (e.key === "ArrowDown") to = enabled[(at + 1) % enabled.length];
    else if (e.key === "ArrowUp") to = enabled[at <= 0 ? enabled.length - 1 : at - 1];
    else if (e.key === "Home") to = enabled[0];
    else if (e.key === "End") to = enabled[enabled.length - 1];
    else return;
    e.preventDefault();
    to?.focus();
  };

  return (
    <div ref={ref} className={cn("relative shrink-0", className)} onBlur={closeOnFocusOut(ref, () => close(false))}>
      <IconButton
        ref={triggerRef}
        variant="secondary"
        label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => (open ? close(false) : setOpen(true))}
      >
        <Ellipsis size={16} strokeWidth={1.75} aria-hidden="true" />
      </IconButton>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={label}
          tabIndex={-1}
          className="absolute right-0 top-full z-50 mt-1 w-[200px] rounded-card border border-border bg-overlay p-1 outline-none"
          style={{ boxShadow: "var(--shadow-md)" }}
          onKeyDown={onKeyDown}
        >
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              tabIndex={-1}
              className={ITEM}
              disabled={item.disabled}
              onClick={() => {
                close(true);
                item.onSelect();
              }}
            >
              {item.icon && <span className={cn("flex shrink-0", item.danger && "text-error")}>{item.icon}</span>}
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
