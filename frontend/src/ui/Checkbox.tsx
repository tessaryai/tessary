// SPDX-License-Identifier: Apache-2.0
import { cn } from "./cn";

/** A native checkbox in the accent. Pass `label` for the accessible name when no visible label points at it. */
export function Checkbox({
  checked,
  onChange,
  label,
  disabled = false,
  className,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label?: string;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <input
      type="checkbox"
      aria-label={label}
      checked={checked}
      disabled={disabled}
      onChange={(e) => onChange(e.target.checked)}
      className={cn("size-4 shrink-0 cursor-pointer rounded-micro accent-accent disabled:cursor-not-allowed", className)}
    />
  );
}
