// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from "react";
import { cn } from "../../ui/cn";
import { useDensity } from "../../ui/density";
import type { Density } from "../../ui/density";

/** Rendered under Settings → Organization. Theme is not a preference: the app is dark only. The chart palette is in the
 * Metrics header, beside the charts it colors. */

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
}: {
  title: string;
  description: string;
  control: ReactNode;
}) {
  return (
    <div className="flex py-4 items-center justify-between gap-5">
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
        />
    </div>
  );
}
