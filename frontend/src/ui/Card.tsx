// SPDX-License-Identifier: Apache-2.0
import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "./cn";

type CardProps = HTMLAttributes<HTMLDivElement> & {
  variant?: "flat" | "raised";
  interactive?: boolean;
  children: ReactNode;
};

export function Card({ variant = "flat", interactive, className, children, ...rest }: CardProps) {
  const bg = variant === "raised" ? "bg-raised" : "bg-surface";
  return (
    <div
      className={cn(
        "rounded-card",
        bg,
        interactive && "transition-colors hover:bg-hover cursor-pointer",
        className,
      )}
      style={interactive ? { transitionDuration: "var(--duration-micro)" } : undefined}
      {...rest}
    >
      {children}
    </div>
  );
}

export function CardHeader({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("px-5 pt-5 pb-3 flex items-start justify-between gap-3", className)}>{children}</div>;
}

export function CardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("px-5 pb-5", className)}>{children}</div>;
}

export function CardTitle({ children, className }: { children: ReactNode; className?: string }) {
  return <h3 className={cn("text-h3 text-fg", className)}>{children}</h3>;
}

export function CardSubtitle({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn("text-small text-muted mt-1", className)}>{children}</p>;
}

export function Surface({
  variant = "surface",
  children,
  className,
}: {
  variant?: "bg" | "surface" | "raised" | "overlay";
  children: ReactNode;
  className?: string;
}) {
  const bg =
    variant === "bg" ? "bg-bg" : variant === "raised" ? "bg-raised" : variant === "overlay" ? "bg-overlay" : "bg-surface";
  return <div className={cn(bg, className)}>{children}</div>;
}
