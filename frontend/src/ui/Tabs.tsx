// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import { cn } from "./cn";

export type TabItem = { value: string; label: string; to: string };

const VARIANT = {
  /** Page sections: grey text, the current one underlined in the accent. */
  underline: {
    nav: "flex items-end gap-1 border-b border-border",
    item: "-mb-px border-b-2 px-3 pb-2.5 pt-1 text-body",
    current: "border-accent text-fg",
    other: "border-transparent text-muted hover:text-fg",
  },
  /** Sections inside a card: the current one on the selected fill. */
  pill: {
    nav: "flex flex-wrap items-center gap-1",
    item: "rounded-control px-2.5 py-1.5 text-small",
    current: "bg-selected text-fg",
    other: "text-muted hover:bg-hover hover:text-fg",
  },
};

/** Tabs that are links, so each section has its own URL; the current one carries `aria-current="page"`. */
export function TabNav({
  label,
  items,
  value,
  variant = "underline",
  className,
}: {
  label: string;
  items: TabItem[];
  value: string;
  variant?: keyof typeof VARIANT;
  className?: string;
}) {
  const v = VARIANT[variant];
  return (
    <nav aria-label={label} className={cn(v.nav, className)}>
      {items.map((item) => {
        const current = item.value === value;
        return (
          <Link
            key={item.value}
            to={item.to}
            aria-current={current ? "page" : undefined}
            className={cn(v.item, "transition-colors", current ? v.current : v.other)}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
