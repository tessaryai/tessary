// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "./cn";

export function EmptyState({
  icon,
  title,
  body,
  action,
  secondary,
  className,
}: {
  icon?: ReactNode;
  title: ReactNode;
  body?: ReactNode;
  action?: ReactNode;
  secondary?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center text-center px-6 py-16 mx-auto max-w-md",
        className,
      )}
    >
      {icon && <div className="mb-4 text-muted">{icon}</div>}
      <h3 className="text-h2 text-fg mb-2">{title}</h3>
      {body && <p className="text-body text-muted mb-6">{body}</p>}
      {(action || secondary) && (
        <div className="flex items-center gap-3">
          {action}
          {secondary}
        </div>
      )}
    </div>
  );
}
