// SPDX-License-Identifier: Apache-2.0
import type { HTMLAttributes, ReactNode, Ref, ThHTMLAttributes, TdHTMLAttributes } from "react";
import { cn, hasTypeSize } from "./cn";

export function Table({ className, children, ...rest }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto rounded-card border border-border">
      <table
        // `text-small` is a default, not a floor: a caller's own scale size must win (see `hasTypeSize`).
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

export function TBody({
  children,
  className,
  ref,
}: {
  children: ReactNode;
  className?: string;
  ref?: Ref<HTMLTableSectionElement>;
}) {
  return (
    <tbody ref={ref} className={cn("divide-y divide-border", className)}>
      {children}
    </tbody>
  );
}

export function TR({ interactive, className, children, ...rest }: HTMLAttributes<HTMLTableRowElement> & { interactive?: boolean }) {
  return (
    // No hover fade: a background transition repaints the whole table on every frame it runs, and a
    // wheel scroll drags the pointer across a fresh row every few frames.
    <tr className={cn(interactive && "cursor-pointer hover:bg-hover", className)} {...rest}>
      {children}
    </tr>
  );
}

export function TH({ className, children, style, ...rest }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      scope="col"
      className={cn(
        "text-left text-column-header text-muted whitespace-nowrap border-b border-border",
        className,
      )}
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
