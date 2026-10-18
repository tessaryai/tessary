// SPDX-License-Identifier: Apache-2.0
import { useEffect, useId, useRef } from "react";
import { EllipsisVertical, Trash } from "lucide-react";
import { IconButton } from "../../ui";
import { closeOnFocusOut, itemsOf } from "../../ui/menuKeys";
import { useDropdown } from "../../ui/useDropdown";

const ITEM =
  "flex w-full items-center gap-2 h-8 px-2.5 rounded-control text-left text-small text-fg-secondary " +
  "hover:bg-hover hover:text-fg focus:bg-hover focus:text-fg outline-none disabled:opacity-50 disabled:cursor-not-allowed";

export function WidgetMenu({
  title,
  canEarlier,
  canLater,
  onEdit,
  onEarlier,
  onLater,
  onRemove,
}: {
  title: string;
  canEarlier: boolean;
  canLater: boolean;
  onEdit: () => void;
  onEarlier: () => void;
  onLater: () => void;
  onRemove: () => void;
}) {
  const { open, setOpen, ref } = useDropdown();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const label = `${title} actions`;

  /** A disabled move cannot take focus, so the arrows step over it. */
  const enabledItems = () => itemsOf(menuRef.current, "menuitem").filter((el) => !(el as HTMLButtonElement).disabled);

  useEffect(() => {
    if (open) enabledItems()[0].focus();
  }, [open]);

  const close = (refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  };

  const choose = (action: () => void) => () => {
    close(true);
    action();
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const items = enabledItems();
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
    // The drag start is cancelled from this root, which holds the open menu too, not from the trigger alone.
    <div ref={ref} data-no-drag="" className="relative shrink-0" onBlur={closeOnFocusOut(ref, () => close(false))}>
      <IconButton
        ref={triggerRef}
        size="sm"
        label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => (open ? close(false) : setOpen(true))}
      >
        <EllipsisVertical size={16} strokeWidth={1.75} aria-hidden="true" />
      </IconButton>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={label}
          tabIndex={-1}
          className="absolute right-0 top-full mt-1 w-[188px] rounded-card bg-overlay border border-border p-1 z-50 outline-none"
          style={{ boxShadow: "var(--shadow-md)" }}
          onKeyDown={onKeyDown}
        >
          <button type="button" role="menuitem" tabIndex={-1} className={ITEM} onClick={choose(onEdit)}>
            Edit
          </button>
          <button type="button" role="menuitem" tabIndex={-1} className={ITEM} disabled={!canEarlier} onClick={choose(onEarlier)}>
            Move earlier
          </button>
          <button type="button" role="menuitem" tabIndex={-1} className={ITEM} disabled={!canLater} onClick={choose(onLater)}>
            Move later
          </button>
          <div role="separator" className="border-t border-border my-1" />
          <button type="button" role="menuitem" tabIndex={-1} className={ITEM} onClick={choose(onRemove)}>
            <Trash size={14} strokeWidth={1.75} aria-hidden="true" className="text-error" />
            Remove
          </button>
        </div>
      )}
    </div>
  );
}
