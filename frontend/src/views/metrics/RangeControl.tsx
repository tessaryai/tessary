// SPDX-License-Identifier: Apache-2.0
// The inputs are datetime-local, so they read and write the browser's local time; the page subtitle prints the
// applied window in UTC.
import { useState } from "react";
import { Button, Field, Input, isoToLocal, localToIso, SegmentedControl } from "../../ui";
import { useDropdown } from "../../ui/useDropdown";
import { RANGE_PRESETS, type RangeState, validateWindow } from "./metricsRules";
import type { MetricsPreset } from "./wire";

type Choice = MetricsPreset | "custom";

const OPTIONS: { value: Choice; label: string }[] = [
  ...RANGE_PRESETS.map((p) => ({ value: p.key, label: p.key })),
  { value: "custom", label: "Custom" },
];

export function RangeControl({ range, onChange }: { range: RangeState; onChange: (next: RangeState) => void }) {
  const { open, setOpen, ref } = useDropdown();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [touched, setTouched] = useState(false);

  const checked = validateWindow(localToIso(from), localToIso(to));
  const error = "error" in checked ? checked.error : undefined;
  const picked = "window" in checked ? checked.window : null;

  const choose = (choice: Choice) => {
    if (choice !== "custom") {
      setOpen(false);
      onChange({ kind: "preset", key: choice });
      return;
    }
    setFrom(range.kind === "custom" ? isoToLocal(range.from) : "");
    setTo(range.kind === "custom" ? isoToLocal(range.to) : "");
    setTouched(false);
    setOpen(true);
  };

  const edit = (set: (v: string) => void) => (e: React.ChangeEvent<HTMLInputElement>) => {
    set(e.target.value);
    setTouched(true);
  };

  /** Apply is disabled while a message stands, so a click always carries a valid window. */
  const apply = () => {
    setOpen(false);
    onChange({ kind: "custom", ...picked! });
  };

  return (
    <div ref={ref} className="relative">
      <SegmentedControl<Choice>
        ariaLabel="Window"
        value={range.kind === "custom" ? "custom" : range.key}
        onChange={choose}
        options={OPTIONS}
      />
      {open && (
        <div
          role="group"
          aria-label="Custom range"
          className="absolute right-0 top-full mt-2 w-[360px] rounded-card bg-overlay border border-border p-4 flex flex-col gap-3 z-50"
          style={{ boxShadow: "var(--shadow-md)" }}
        >
          <p className="text-label uppercase text-muted">Custom range</p>
          <Field label="From">
            {(p) => (
              <Input {...p} type="datetime-local" className="font-mono" value={from} onChange={edit(setFrom)} />
            )}
          </Field>
          <Field label="To" error={touched ? error : undefined}>
            {(p) => <Input {...p} type="datetime-local" className="font-mono" value={to} onChange={edit(setTo)} />}
          </Field>
          <div className="flex items-center justify-end gap-2">
            <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="sm" variant="primary" disabled={error != null} onClick={apply}>
              Apply
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
