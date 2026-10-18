// SPDX-License-Identifier: Apache-2.0
import { useId, useRef } from "react";
import type { KeyboardEvent, ReactNode } from "react";
import { Badge } from "../../ui";
import { CHART_PALETTES, useChartPalette } from "../../ui/chartPalette";
import type { ChartPalette } from "../../ui/chartPalette";
import { cn } from "../../ui/cn";
import { useDensity } from "../../ui/density";
import type { Density } from "../../ui/density";

/** Rendered under Settings → Organization. Theme is not a preference: the app is dark only. */

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
  stacked,
}: {
  title: string;
  description: string;
  control: ReactNode;
  last?: boolean;
  stacked?: boolean;
}) {
  return (
    <div
      className={cn(
        "flex py-4",
        stacked ? "flex-col items-stretch gap-3" : "items-center justify-between gap-5",
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

const PALETTE_NOTES: Record<ChartPalette, string> = {
  tessary: "Hue for chart series. The default.",
  colorblind: "Series stay apart for the common kinds of color blindness.",
  greyscale: "Grey steps only. Line dashes and widths tell series apart.",
};

const SERIES = [1, 2, 3, 4, 5, 6];

const NEXT_KEYS = new Set(["ArrowRight", "ArrowDown"]);
const PREVIOUS_KEYS = new Set(["ArrowLeft", "ArrowUp"]);

/** The APG radio group: arrows move and select, wrapping; only the checked card is in the tab order. */
function PalettePicker() {
  const { palette, setPalette } = useChartPalette();
  const cards = useRef<(HTMLDivElement | null)[]>([]);
  const id = useId();

  const onKeyDown = (index: number) => (e: KeyboardEvent) => {
    const step = NEXT_KEYS.has(e.key) ? 1 : PREVIOUS_KEYS.has(e.key) ? -1 : 0;
    if (step !== 0) {
      e.preventDefault();
      const next = (index + step + CHART_PALETTES.length) % CHART_PALETTES.length;
      setPalette(CHART_PALETTES[next].value);
      cards.current[next]!.focus();
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      setPalette(CHART_PALETTES[index].value);
    }
  };

  return (
    <div role="radiogroup" aria-label="Chart palette" className="grid grid-cols-3 gap-4">
      {CHART_PALETTES.map((option, index) => {
        const checked = option.value === palette;
        return (
          <div
            key={option.value}
            ref={(el) => {
              cards.current[index] = el;
            }}
            role="radio"
            aria-checked={checked}
            aria-labelledby={`${id}-${option.value}-name`}
            aria-describedby={`${id}-${option.value}-note`}
            tabIndex={checked ? 0 : -1}
            onClick={() => setPalette(option.value)}
            onKeyDown={onKeyDown(index)}
            className={cn(
              "rounded-card border p-4 flex flex-col gap-3 cursor-pointer",
              checked
                ? "bg-raised border-accent-edge shadow-[inset_0_0_0_1px_var(--color-accent-edge)]"
                : "bg-surface border-border",
            )}
          >
            <div className="flex items-center gap-2.5">
              <span
                className={cn(
                  "inline-flex size-4 shrink-0 items-center justify-center rounded-pill border",
                  checked ? "border-accent" : "border-border-strong",
                )}
              >
                {checked && <span className="size-2 rounded-pill bg-accent" />}
              </span>
              <span id={`${id}-${option.value}-name`} className="text-body font-medium text-fg">
                {option.label}
              </span>
              {option.value === "tessary" && <Badge tone="neutral">Default</Badge>}
            </div>
            <div aria-hidden className="flex items-center gap-2">
              {SERIES.map((n) => (
                <span
                  key={n}
                  className="size-7 rounded-pill"
                  style={{ background: `var(--color-chart-${option.value}-${n})` }}
                />
              ))}
              <span
                title="Other"
                className="size-7 rounded-pill border-2 border-dashed"
                style={{ borderColor: `var(--color-chart-${option.value}-other)` }}
              />
            </div>
            <div id={`${id}-${option.value}-note`} className="text-small text-muted">
              {PALETTE_NOTES[option.value]}
            </div>
          </div>
        );
      })}
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
        <Row
          title="Chart palette"
          description="Colors for chart series. Status colors stay the same everywhere else. Saved in this browser."
          control={<PalettePicker />}
          stacked
          last
        />
    </div>
  );
}
