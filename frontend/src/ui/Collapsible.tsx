// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "./cn";

/** A bordered `<details>` disclosure with a title row and optional inline meta. */
export function Collapsible({
  title,
  meta,
  defaultOpen,
  onToggle,
  children,
}: {
  title: string;
  meta?: ReactNode;
  defaultOpen?: boolean;
  /** Fires with the open state on every expand/collapse (e.g. to gate lazy queries). */
  onToggle?: (open: boolean) => void;
  children: ReactNode;
}) {
  return (
    <details
      open={defaultOpen}
      onToggle={onToggle ? (e) => onToggle(e.currentTarget.open) : undefined}
      className={cn("rounded-control border border-border")}
    >
      <summary className="flex cursor-pointer select-none items-center gap-2 px-3 py-2 text-small text-fg hover:bg-hover">
        <span className="font-medium">{title}</span>
        {meta}
      </summary>
      <div className="px-3 pb-3 pt-1">{children}</div>
    </details>
  );
}
