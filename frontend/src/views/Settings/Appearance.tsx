// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "../../ui/cn";
import { useDensity } from "../../ui/density";
import type { Density } from "../../ui/density";

/**
 * Account-level display preferences, rendered as a section under Settings →
 * Organization via {@link AppearanceControls}.
 *
 * Theme is NOT a preference — the app is dark only (2026-07 redesign); the
 * toggle was removed with the light theme. Density is wired to its live context
 * so changes apply app-wide immediately.
 */

/** Compact two-option segmented control, token-driven to match the design. */
function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T;
  options: { value: T; label: string }[];
  onChange: (next: T) => void;
  label: string;
}) {
  return (
    <div
      role="radiogroup"
      aria-label={label}
      className="inline-flex shrink-0 items-center gap-0.5 rounded-control border border-border bg-surface p-[3px]">
      {options.map((opt) => {
        const active = value === opt.value;
        return (
          <button
            key={opt.value}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(opt.value)}
            className={cn(
              "rounded-control px-3.5 py-1.5 text-small transition-colors",
              active
                ? "bg-raised text-fg font-medium shadow-[inset_0_0_0_1px_var(--color-border-strong)]"
                : "text-muted hover:text-fg",
            )}
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

function Row({
  title,
  description,
  control,
  last,
}: {
  title: string;
  description: string;
  control: ReactNode;
  last?: boolean;
}) {
  return (
    <div
      className={cn(
        "flex items-center justify-between gap-5 py-4",
        !last && "border-b border-border",
      )}
    >
      <div className="min-w-0">
        <div className="text-body font-semibold text-fg">{title}</div>
        <div className="text-small text-muted mt-0.5">{description}</div>
      </div>
      {control}
    </div>
  );
}

export function AppearanceControls() {
  const { density, setDensity } = useDensity();

  return (
    <div className="flex flex-col">
        <Row
          title="Density"
          description="Row spacing across every data view."
          control={
            <Segmented<Density>
              label="Density"
              value={density}
              onChange={setDensity}
              options={[
                { value: "comfortable", label: "Comfortable" },
                { value: "compact", label: "Compact" },
              ]}
            />
          }
          last
        />
    </div>
  );
}
