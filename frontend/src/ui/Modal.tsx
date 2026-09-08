// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { useEffect, useRef } from "react";
import { X } from "lucide-react";
import { cn } from "./cn";
import { IconButton } from "./Button";

type Size = "sm" | "md" | "lg";

const WIDTH: Record<Size, string> = {
  sm: "w-[min(90vw,400px)]",
  md: "w-[min(90vw,560px)]",
  lg: "w-[min(90vw,800px)]",
};

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  size = "md",
  className,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  size?: Size;
  className?: string;
}) {
  const dlgRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dlg = dlgRef.current;
    if (!dlg) return;
    if (open && !dlg.open) dlg.showModal();
    if (!open && dlg.open) dlg.close();
  }, [open]);

  useEffect(() => {
    const dlg = dlgRef.current;
    if (!dlg) return;
    const onCancel = (e: Event) => {
      e.preventDefault();
      onClose();
    };
    const onClick = (e: MouseEvent) => {
      if (e.target === dlg) onClose();
    };
    dlg.addEventListener("cancel", onCancel);
    dlg.addEventListener("click", onClick);
    return () => {
      dlg.removeEventListener("cancel", onCancel);
      dlg.removeEventListener("click", onClick);
    };
  }, [onClose]);

  return (
    <dialog
      ref={dlgRef}
      className={cn(
        "p-0 m-auto rounded-modal bg-surface text-fg border border-border-strong",
        "backdrop:bg-scrim backdrop:backdrop-blur-sm",
        WIDTH[size],
        className,
      )}
      style={{ boxShadow: "var(--shadow-md)" }}
    >
      <div className="flex items-start justify-between gap-4 px-6 pt-5 pb-3">
        <div className="min-w-0">
          <h2 className="text-h2 text-fg">{title}</h2>
          {subtitle && <p className="text-small text-muted mt-1">{subtitle}</p>}
        </div>
        <IconButton label="Close" onClick={onClose} size="sm">
          <X size={14} strokeWidth={1.75} aria-hidden="true" />
        </IconButton>
      </div>
      <div className="px-6 pb-5">{children}</div>
      {footer && (
        <div className="flex items-center justify-end gap-2 px-6 py-4 border-t border-border bg-surface rounded-b-modal">
          {footer}
        </div>
      )}
    </dialog>
  );
}
