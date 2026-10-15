// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

export interface Segment<T extends string> {
  value: T;
  label: string;
}

export function SegmentedControl<T extends string>({
  value,
  onChange,
  options,
  ariaLabel,
}: {
  value: T;
  onChange: (value: T) => void;
  options: Segment<T>[];
  ariaLabel: string;
}) {
  return (
    <div role="group" aria-label={ariaLabel} className="flex items-center gap-1 rounded-pill bg-surface p-0.5">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          aria-pressed={value === opt.value}
          className={cn(
            "px-2.5 py-1 rounded-pill text-small transition-colors",
            value === opt.value ? "bg-selected text-fg" : "text-muted hover:text-fg hover:bg-hover",
          )}
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
