// SPDX-License-Identifier: Apache-2.0
import type { CSSProperties } from "react";
import { Loader2 } from "lucide-react";
import { cn } from "./cn";

type Size = "sm" | "md" | "lg";

export function Spinner({ size = "md", className }: { size?: Size; className?: string }) {
  const px = size === "sm" ? 12 : size === "lg" ? 20 : 16;
  return (
    <Loader2
      className={cn("animate-spin text-muted", className)}
      width={px}
      height={px}
      strokeWidth={2.5}
      aria-hidden="true"
    />
  );
}

export function Skeleton({ className, style }: { className?: string; style?: CSSProperties }) {
  return (
    <div
      className={cn("rounded-control bg-raised animate-pulse", className)}
      style={{ animationDuration: "var(--duration-reveal)", ...style }}
      aria-hidden="true"
    />
  );
}
