// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "./cn";

export type Tab<T extends string> = { id: T; label: ReactNode; count?: number };

export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
  className,
}: {
  tabs: ReadonlyArray<Tab<T>>;
  value: T;
  onChange: (id: T) => void;
  className?: string;
}) {
  return (
    <div role="tablist" className={cn("flex items-center gap-1 border-b border-border", className)}>
      {tabs.map((t) => {
        const active = t.id === value;
        return (
          <button
            key={t.id}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(t.id)}
            className={cn(
              "relative px-3 py-2 text-small transition-colors",
              active ? "text-fg" : "text-muted hover:text-fg",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {t.label}
            {typeof t.count === "number" && (
              <span className="ml-2 text-label text-subtle">{t.count}</span>
            )}
            {active && (
              <span
                aria-hidden="true"
                className="absolute left-2 right-2 -bottom-px h-0.5 bg-accent rounded-pill"
              />
            )}
          </button>
        );
      })}
    </div>
  );
}
