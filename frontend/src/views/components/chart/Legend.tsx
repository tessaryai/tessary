// SPDX-License-Identifier: Apache-2.0
import type { Ref } from "react";
import { cn } from "../../../ui";

export function LegendDot({
  colour,
  label,
  dashed,
  dash,
}: {
  colour: string;
  label: string;
  dashed?: boolean;
  /** A line swatch drawn with this SVG dash pattern ("" is solid), matching a line chart's series. */
  dash?: string;
}) {
  return (
    <span className="inline-flex items-center gap-1.5">
      {dash != null ? (
        <svg aria-hidden width="16" height="8" className="shrink-0">
          <line x1="0" x2="16" y1="4" y2="4" stroke={colour} strokeWidth={2} strokeDasharray={dash || undefined} />
        </svg>
      ) : dashed ? (
        <span aria-hidden className="inline-block" style={{ width: 12, borderTop: `2px dashed ${colour}` }} />
      ) : (
        <span aria-hidden className="inline-block rounded-pill" style={{ width: 8, height: 8, background: colour }} />
      )}
      {label}
    </span>
  );
}

export function Legend({
  items,
  className,
  ref,
}: {
  items: { key: string; label: string; colour: string; dashed?: boolean; dash?: string }[];
  className?: string;
  ref?: Ref<HTMLUListElement>;
}) {
  return (
    <ul ref={ref} aria-label="Legend" className={cn("flex flex-wrap gap-x-4.5 gap-y-1 text-small text-muted", className)}>
      {items.map((item) => (
        <li key={item.key}>
          <LegendDot colour={item.colour} label={item.label} dashed={item.dashed} dash={item.dash} />
        </li>
      ))}
    </ul>
  );
}
