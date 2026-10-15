// SPDX-License-Identifier: Apache-2.0
import { cn } from "../../ui";

export function SourceGlyph({ provider, className }: { provider: string; className?: string }) {
  return (
    <span
      className={cn(
        "flex size-8 shrink-0 items-center justify-center rounded-control bg-raised font-mono text-small text-fg",
        className,
      )}
    >
      {(provider[0] ?? "S").toUpperCase()}
    </span>
  );
}
