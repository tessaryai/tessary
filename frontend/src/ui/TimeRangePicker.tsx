// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from "react";
import { Input } from "./Input";
import { type TimeRange, formatRange } from "./timeRange";

/**
 * Grafana-style time range: two text fields. Each accepts a relative token
 * ("now", "now-1d", "now-6h") or an absolute date — the backend resolves tokens
 * at fetch time. Edits commit on blur / Enter so a re-pull only fires once the
 * user is done typing, not on every keystroke.
 */
export function TimeRangePicker({
  value,
  onChange,
}: {
  value: TimeRange;
  onChange: (v: TimeRange) => void;
}) {
  const [from, setFrom] = useState(value.from ?? "");
  const [to, setTo] = useState(value.to ?? "");

  // Stay in sync when the range is reset externally (e.g. switching source).
  useEffect(() => {
    setFrom(value.from ?? "");
    setTo(value.to ?? "");
  }, [value.from, value.to]);

  const commit = (nextFrom: string, nextTo: string) =>
    onChange({ from: nextFrom.trim() || null, to: nextTo.trim() || null });

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") (e.target as HTMLInputElement).blur();
  };

  return (
    <div className="space-y-1.5">
      <div className="grid grid-cols-2 gap-3">
        <label className="space-y-1">
          <span className="text-label uppercase text-muted">From</span>
          <Input
            value={from}
            placeholder="now-1d"
            className="font-mono"
            onChange={(e) => setFrom(e.target.value)}
            onBlur={() => commit(from, to)}
            onKeyDown={onKey}
          />
        </label>
        <label className="space-y-1">
          <span className="text-label uppercase text-muted">To</span>
          <Input
            value={to}
            placeholder="now"
            className="font-mono"
            onChange={(e) => setTo(e.target.value)}
            onBlur={() => commit(from, to)}
            onKeyDown={onKey}
          />
        </label>
      </div>
      <p className="text-small text-subtle">
        Relative (<code className="font-mono">now</code>, <code className="font-mono">now-1d</code>,{" "}
        <code className="font-mono">now-6h</code>) or an absolute date. Leave blank for no bound.{" "}
        <span className="text-muted">{formatRange({ from: from.trim() || null, to: to.trim() || null })}</span>
      </p>
    </div>
  );
}
