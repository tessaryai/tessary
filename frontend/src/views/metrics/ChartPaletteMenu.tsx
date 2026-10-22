// SPDX-License-Identifier: Apache-2.0
// The chart palette is a per-browser choice and only Metrics charts read it, so it lives in the Metrics header. The
// trigger is three dots in the active palette's first colors; its name is the accessible label and the tooltip.
import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";
import { Button } from "../../ui";
import { CHART_PALETTES, useChartPalette } from "../../ui/chartPalette";
import { cn } from "../../ui/cn";
import { useDropdown } from "../../ui/useDropdown";

const SERIES = [1, 2, 3, 4, 5, 6];
const NEXT_KEYS = new Set(["ArrowRight", "ArrowDown"]);
const PREVIOUS_KEYS = new Set(["ArrowLeft", "ArrowUp"]);

export function ChartPaletteMenu() {
  const { palette, setPalette } = useChartPalette();
  const { open, setOpen, ref } = useDropdown();
  const trigger = useRef<HTMLButtonElement>(null);
  const rows = useRef<(HTMLDivElement | null)[]>([]);
  const checkedIndex = CHART_PALETTES.findIndex((p) => p.value === palette);

  // Opening moves focus to the checked palette, as an APG radio group expects.
  useEffect(() => {
    if (open) rows.current[checkedIndex]?.focus();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only on open, not on every pick
  }, [open]);

  /** Arrows move and select, wrapping; only the checked row is in the tab order. Escape returns to the trigger. */
  const onKeyDown = (index: number) => (e: KeyboardEvent) => {
    const step = NEXT_KEYS.has(e.key) ? 1 : PREVIOUS_KEYS.has(e.key) ? -1 : 0;
    if (step !== 0) {
      e.preventDefault();
      const next = (index + step + CHART_PALETTES.length) % CHART_PALETTES.length;
      setPalette(CHART_PALETTES[next].value);
      rows.current[next]!.focus();
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      setPalette(CHART_PALETTES[index].value);
    } else if (e.key === "Escape") {
      setOpen(false);
      trigger.current!.focus();
    }
  };

  return (
    <div ref={ref} className="relative">
      <Button
        ref={trigger}
        variant="secondary"
        aria-label="Chart colors"
        title="Chart colors"
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <span aria-hidden="true" className="inline-flex items-center gap-[3px]">
          {[1, 2, 3].map((n) => (
            <span key={n} className="size-2 rounded-pill" style={{ background: `var(--color-series-${n})` }} />
          ))}
        </span>
      </Button>
      {open && (
        <div
          role="dialog"
          aria-label="Chart colors"
          className="absolute right-0 top-full mt-2 w-[288px] rounded-card bg-overlay border border-border p-1.5 flex flex-col gap-0.5 z-50"
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          <p className="text-label uppercase text-muted px-2 pt-1.5 pb-1">Chart colors</p>
          <div role="radiogroup" aria-label="Chart palette" className="flex flex-col gap-0.5">
            {CHART_PALETTES.map((option, index) => {
              const checked = option.value === palette;
              return (
                <div
                  key={option.value}
                  ref={(el) => {
                    rows.current[index] = el;
                  }}
                  role="radio"
                  aria-checked={checked}
                  tabIndex={checked ? 0 : -1}
                  onClick={() => setPalette(option.value)}
                  onKeyDown={onKeyDown(index)}
                  className={cn(
                    "h-9 px-2 flex items-center gap-2.5 rounded-control cursor-pointer",
                    checked ? "bg-raised" : "hover:bg-hover",
                  )}
                >
                  <span
                    className={cn(
                      "inline-flex size-4 shrink-0 items-center justify-center rounded-pill border",
                      checked ? "border-accent" : "border-border-strong",
                    )}
                  >
                    {checked && <span className="size-2 rounded-pill bg-accent" />}
                  </span>
                  <span className="flex-1 min-w-0 text-small text-fg">{option.label}</span>
                  <span aria-hidden="true" className="flex items-center gap-1">
                    {SERIES.map((n) => (
                      <span
                        key={n}
                        className="size-2.5 rounded-pill"
                        style={{ background: `var(--color-chart-${option.value}-${n})` }}
                      />
                    ))}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
