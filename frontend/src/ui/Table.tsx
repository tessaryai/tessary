// SPDX-License-Identifier: Apache-2.0
import type { HTMLAttributes, ReactNode, ThHTMLAttributes, TdHTMLAttributes } from "react";
import { cn, hasTypeSize } from "./cn";

export function Table({ className, children, ...rest }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto rounded-card border border-border">
      <table
        // `text-small` is the default, not a floor: a caller that names its own size on the scale
        // must win, and without this it would not (see `hasTypeSize`). The traces table asks for
        // `text-body` and was silently rendering at 12px.
        className={cn("w-full text-fg border-collapse", !hasTypeSize(className) && "text-small", className)}
        {...rest}
      >
        {children}
      </table>
    </div>
  );
}

export function THead({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <thead className={cn("bg-surface sticky top-0 z-10", className)}>
      {children}
    </thead>
  );
}

export function TBody({ children, className }: { children: ReactNode; className?: string }) {
  return <tbody className={cn("divide-y divide-border", className)}>{children}</tbody>;
}

export function TR({ interactive, className, children, ...rest }: HTMLAttributes<HTMLTableRowElement> & { interactive?: boolean }) {
  return (
    <tr
      className={cn(interactive && "cursor-pointer transition-colors hover:bg-hover", className)}
      style={interactive ? { transitionDuration: "var(--duration-micro)" } : undefined}
      {...rest}
    >
      {children}
    </tr>
  );
}

export function TH({ className, children, style, ...rest }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      scope="col"
      // `text-column-header`, not the `text-label` eyebrow: the type spec keeps them apart
      // deliberately — a column header is sentence case and untracked, an eyebrow is UPPERCASE
      // and tracked. The token carries its own size, weight and leading, so no font-* here.
      className={cn(
        "text-left text-column-header text-muted whitespace-nowrap border-b border-border",
        className,
      )}
      // Density-aware: horizontal padding follows --density-cell-px; header
      // vertical padding stays tight regardless of density.
      style={{ paddingInline: "var(--density-cell-px)", paddingBlock: "0.5rem", ...style }}
      {...rest}
    >
      {children}
    </th>
  );
}

export function TD({ className, children, style, ...rest }: TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td
      className={cn("align-top text-fg", className)}
      // Density-aware padding + line-height; re-flows under [data-density].
      style={{
        paddingInline: "var(--density-cell-px)",
        paddingBlock: "var(--density-cell-py)",
        lineHeight: "var(--density-row-leading)",
        ...style }}
      {...rest}
    >
      {children}
    </td>
  );
}
