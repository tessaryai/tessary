// SPDX-License-Identifier: Apache-2.0

export function Tooltip({ left, top, transform, lines }: { left: number; top: string; transform: string; lines: string[] }) {
  return (
    <div
      role="tooltip"
      className="absolute z-10 pointer-events-none flex flex-col rounded-card bg-overlay border border-border px-2.5 py-2 whitespace-nowrap text-small tabular-nums"
      style={{ left: `${left}%`, top, transform, boxShadow: "var(--shadow-md)" }}
    >
      {lines.map((line, i) => (
        <span key={i} className={i === 0 ? "text-fg" : "text-muted"}>
          {line}
        </span>
      ))}
    </div>
  );
}
