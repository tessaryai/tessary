// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

export interface Segment<T extends string> {
  value: T;
  label: string;
  /** Native tooltip — use it to say why an option is unavailable. */
  title?: string;
  disabled?: boolean;
}

/**
 * A pill group for a small, mutually exclusive set of options — chart windows, bucket grains, view
 * modes. Use it over a `<Select>` when the options are few enough to read at a glance and the reader
 * benefits from seeing the alternatives without opening anything.
 *
 * A disabled segment stays visible rather than disappearing: an option that vanishes when another
 * control changes reads as a bug, one that dims with a `title` reads as a rule.
 */
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
          disabled={opt.disabled}
          title={opt.title}
          aria-pressed={value === opt.value}
          className={cn(
            "px-2.5 py-1 rounded-pill text-small transition-colors",
            opt.disabled
              ? "text-subtle cursor-not-allowed"
              : value === opt.value
                ? "bg-selected text-fg"
                : "text-muted hover:text-fg hover:bg-hover",
          )}
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}
