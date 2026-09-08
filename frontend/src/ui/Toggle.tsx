// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

/**
 * Small accessible pill toggle switch.
 *
 * Token-driven: the track is `bg-accent` when checked and `bg-border-strong` when
 * off, and the thumb takes whichever color reads on the track under it — the
 * accent's own label color on the light checked track, primary text on the dark
 * unchecked one. ~34×19px track, 15px thumb.
 *
 * Renders as a `role="switch"` button; pass `label` for the accessible name when
 * there is no adjacent visible label wired via `aria-labelledby`.
 */
export function Toggle({
  checked,
  onChange,
  label,
  className,
  disabled = false,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label?: string;
  className?: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        // focus ring comes from the global :focus-visible rule in index.css
        "relative inline-flex h-[19px] w-[34px] shrink-0 items-center rounded-pill transition-colors",
        checked ? "bg-accent" : "bg-border-strong",
        disabled && "opacity-50 cursor-not-allowed",
        className,
      )}
      style={{ transitionDuration: "var(--duration-micro)" }}
    >
      <span
        className={cn(
          "pointer-events-none absolute top-[2px] block size-[15px] rounded-pill transition-[left]",
          checked ? "left-[17px] bg-accent-text-on" : "left-[2px] bg-fg",
        )}
        style={{ transitionDuration: "var(--duration-micro)" }}
      />
    </button>
  );
}
