// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

/** Pass `label` for the accessible name when no visible label is wired via `aria-labelledby`. */
export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label?: string;
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
        "relative inline-flex h-[19px] w-[34px] shrink-0 items-center rounded-pill transition-colors",
        checked ? "bg-accent" : "bg-border-strong",
        disabled && "opacity-50 cursor-not-allowed",
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
