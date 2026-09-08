// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "./cn";

export type BadgeTone = "neutral" | "accent" | "success" | "warning" | "error" | "info";

const TONE: Record<BadgeTone, string> = {
  neutral: "bg-raised text-muted",
  accent: "text-accent",
  success: "text-success",
  warning: "text-warning",
  error: "text-error",
  info: "text-info",
};

export function Badge({
  tone = "neutral",
  children,
  className,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}) {
  const subtleBg =
    tone === "success"
      ? { backgroundColor: "var(--color-success-subtle)" }
      : tone === "warning"
      ? { backgroundColor: "var(--color-warning-subtle)" }
      : tone === "error"
      ? { backgroundColor: "var(--color-error-subtle)" }
      : tone === "info"
      ? { backgroundColor: "var(--color-info-subtle)" }
      : tone === "accent"
      ? { backgroundColor: "var(--color-accent-subtle)" }
      : undefined;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-pill px-2 py-0.5 text-label",
        TONE[tone],
        className,
      )}
      style={subtleBg}
    >
      {children}
    </span>
  );
}
