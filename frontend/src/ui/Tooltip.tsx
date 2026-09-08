// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { useState } from "react";
import { cn } from "./cn";

type Placement = "top" | "bottom";

export function Tooltip({
  content,
  placement = "top",
  children,
  className,
}: {
  content: ReactNode;
  placement?: Placement;
  children: ReactNode;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  return (
    <span
      className={cn("relative inline-flex", className)}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      {children}
      {open && (
        <span
          role="tooltip"
          className={cn(
            "pointer-events-none absolute left-1/2 -translate-x-1/2 z-50",
            "rounded-control bg-overlay text-fg text-small px-2 py-1 shadow-md whitespace-nowrap",
            placement === "top" ? "bottom-full mb-1.5" : "top-full mt-1.5",
          )}
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          {content}
        </span>
      )}
    </span>
  );
}
