// SPDX-License-Identifier: Apache-2.0
import { cn } from "../../../ui";

export function LegendDot({ colour, label, dashed }: { colour: string; label: string; dashed?: boolean }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      {dashed ? (
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
}: {
  items: { key: string; label: string; colour: string; dashed?: boolean }[];
  className?: string;
}) {
  return (
    <ul aria-label="Legend" className={cn("flex flex-wrap gap-4.5 text-small text-muted", className)}>
      {items.map((item) => (
        <li key={item.key}>
          <LegendDot colour={item.colour} label={item.label} dashed={item.dashed} />
        </li>
      ))}
    </ul>
  );
}
