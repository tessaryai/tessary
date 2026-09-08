// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

type Orientation = "horizontal" | "vertical";
type Strength = "default" | "strong";

export function Divider({
  orientation = "horizontal",
  strength = "default",
  className,
}: {
  orientation?: Orientation;
  strength?: Strength;
  className?: string;
}) {
  const color = strength === "strong" ? "bg-border-strong" : "bg-border";
  if (orientation === "vertical") {
    return <div role="separator" aria-orientation="vertical" className={cn("w-px self-stretch", color, className)} />;
  }
  return <hr role="separator" className={cn("h-px border-0 w-full", color, className)} />;
}
