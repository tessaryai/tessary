// SPDX-License-Identifier: Apache-2.0
/*
 * An issue's two step lines from the hit day: hit user IDs (always red) against similar user IDs not yet hit (grey).
 * Three forms: a list row (P1), the case block (P4) with its legend under the chart, and the issue page (P2) with the
 * likely range of the hit line, the y axis, the week axis and the at-risk counts. Only "evidence of loss" fills the gap
 * between the lines and makes its label red.
 *
 * The chart's coordinates are CSS pixels: the SVG takes its width from its box, measured, so its text keeps the type
 * scale at every width. The week axis and the at-risk rows are one table whose outer columns match the plot margins.
 */
import type { EndUserCurvePoint, EndUserIssueNumbers } from "../../../api/types";
import { useWidth } from "../../components/chart/useWidth";
import {
  AT_RISK,
  AT_RISK_ROWS,
  chartAria,
  ISSUE_COLUMNS,
  issueRowLabel,
  LARGE_LEGEND,
  lossText,
  lostShort,
  percent,
  RETENTION_ARIA,
  rowChartAria,
  SMALL_LEGEND,
  thousands,
  weekTick,
} from "../usersWords";
import { retentionShapes, WEEKS } from "./retentionRules";

/** A shown issue: the backend sends a curve of weeks 1 to n, n at least 1. */
export type ShownIssue = EndUserIssueNumbers & { curve: EndUserCurvePoint[] };

export type RetentionChartProps = { issue: ShownIssue } & (
  | { variant: "row"; caseRef: string }
  | { variant: "block" }
  | { variant: "page" }
);

const SMALL_TEXT = { line: 14, endText: "text-small", gapText: "text-body font-medium" };
const SIZES = {
  row: { height: 80, left: 4, right: 60, top: 6, bottom: 8, ...SMALL_TEXT },
  block: { height: 110, left: 4, right: 48, top: 6, bottom: 10, ...SMALL_TEXT },
  page: { height: 300, left: 52, right: 64, top: 8, bottom: 20, line: 20, endText: "text-h3", gapText: "text-h3" },
};

const RED = "var(--color-error)";
const WEEK_LIST = Array.from({ length: WEEKS }, (_, i) => i + 1);

export function RetentionChart(props: RetentionChartProps) {
  const { issue, variant } = props;
  const [boxRef, width] = useWidth();
  const size = SIZES[variant];
  const curve = issue.curve;
  const last = curve[curve.length - 1];
  const claimed = issue.verdict === "evidence_of_loss";
  const shapes = retentionShapes(
    curve,
    { left: size.left, right: width - size.right, top: size.top, bottom: size.height - size.bottom },
    size.line,
  );
  const label = variant === "block" && claimed ? lostShort(issue.lost!) : issueRowLabel(issue);
  const aria =
    props.variant === "row"
      ? rowChartAria(props.caseRef, curve.length, last.hit, last.similar, label)
      : chartAria(curve.length, last.hit, last.similar, lossText(issue));
  const large = variant === "page";
  const grid = large ? shapes.ticks : [shapes.ticks[0], shapes.ticks[shapes.ticks.length - 1]];

  const chart = (
    <div ref={boxRef}>
      <svg
        viewBox={`0 0 ${width} ${size.height}`}
        className="block w-full h-auto overflow-visible"
        role="img"
        aria-label={aria}
      >
        {grid.map((t) => (
          <g key={t.value}>
            <line
              x1={size.left}
              x2={width - size.right}
              y1={t.y}
              y2={t.y}
              stroke={t.value === shapes.floor ? "var(--color-border-strong)" : "var(--color-chart-grid)"}
              strokeDasharray={t.value === shapes.floor ? undefined : "2 4"}
            />
            {large && (
              <text
                x={size.left - 8}
                y={t.y}
                textAnchor="end"
                dominantBaseline="middle"
                fill="var(--color-muted)"
                className="text-small"
              >
                {percent(t.value)}
              </text>
            )}
          </g>
        ))}
        {claimed && <polygon data-part="loss" points={shapes.loss} fill={RED} fillOpacity={0.14} />}
        {large && <polygon data-part="band" points={shapes.band} fill={RED} fillOpacity={0.1} />}
        <path data-part="similar" d={shapes.similar} fill="none" stroke="var(--color-muted)" strokeWidth={2} />
        <path data-part="hit" d={shapes.hit} fill="none" stroke={RED} strokeWidth={2.5} />
        <text
          x={shapes.end.x}
          y={shapes.end.similarY}
          dominantBaseline="middle"
          fill="var(--color-fg-secondary)"
          className={size.endText}
        >
          {percent(last.similar)}
        </text>
        <text x={shapes.end.x} y={shapes.end.hitY} dominantBaseline="middle" fill={RED} className={size.endText}>
          {percent(last.hit)}
        </text>
        <text
          x={shapes.gap.x}
          y={shapes.gap.y}
          textAnchor="middle"
          dominantBaseline="middle"
          fill={claimed ? RED : "var(--color-fg-secondary)"}
          className={size.gapText}
        >
          {label}
        </text>
      </svg>
    </div>
  );

  if (variant === "row") return chart;

  if (variant === "block")
    return (
      <div>
        {chart}
        <ul className="mt-1.5 flex flex-wrap gap-x-3.5 gap-y-1 text-small text-muted">
          <li>{ISSUE_COLUMNS.curve}</li>
          <Key swatch="line" colour={RED} label={SMALL_LEGEND.hit} />
          <Key swatch="line" colour="var(--color-muted)" label={SMALL_LEGEND.similar} />
        </ul>
      </div>
    );

  return (
    <section
      aria-label={RETENTION_ARIA}
      className="rounded-card bg-surface border border-border px-5 pt-4 pb-3.5 flex flex-col gap-2"
    >
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1 text-small text-muted">
        <li className="text-body font-medium text-fg">{LARGE_LEGEND.title}</li>
        <Key swatch="line" colour={RED} label={LARGE_LEGEND.hit} />
        <Key swatch="line" colour="var(--color-muted)" label={LARGE_LEGEND.similar} />
        {claimed && <Key swatch="area" opacity={0.25} label={LARGE_LEGEND.lost} />}
        <Key swatch="area" opacity={0.45} label={LARGE_LEGEND.band} />
      </ul>
      {chart}
      <table aria-label={AT_RISK} className="w-full table-fixed text-small text-muted">
        <colgroup>
          <col style={{ width: size.left }} />
          {WEEK_LIST.map((w) => (
            <col key={w} />
          ))}
          <col style={{ width: size.right }} />
        </colgroup>
        <thead>
          <tr>
            <td />
            {WEEK_LIST.map((w) => (
              <th key={w} scope="col" className="font-normal text-center pt-1 pb-2">
                {weekTick(w)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="[&_th]:font-normal [&_th]:text-right [&_th]:pr-2 [&_td]:text-center [&_td]:py-0.5">
          <tr className="border-t border-border">
            <th scope="row" className="pt-2">
              {AT_RISK}
            </th>
          </tr>
          <AtRiskRow label={AT_RISK_ROWS.hit} labelClass="text-error" counts={curve.map((p) => p.at_risk_hit)} />
          <AtRiskRow label={AT_RISK_ROWS.similar} counts={curve.map((p) => p.at_risk_similar)} />
        </tbody>
      </table>
    </section>
  );
}

function AtRiskRow({ label, labelClass, counts }: { label: string; labelClass?: string; counts: number[] }) {
  return (
    <tr>
      <th scope="row" className={labelClass}>
        {label}
      </th>
      {WEEK_LIST.map((w) => (
        <td key={w} className="tabular-nums text-fg-secondary">
          {w <= counts.length ? thousands(counts[w - 1]) : ""}
        </td>
      ))}
    </tr>
  );
}

function Key(
  props: { label: string } & ({ swatch: "line"; colour: string } | { swatch: "area"; opacity: number }),
) {
  return (
    <li className="inline-flex items-center gap-1.5">
      {props.swatch === "line" ? (
        <span aria-hidden className="inline-block w-3.5 h-0.5" style={{ background: props.colour }} />
      ) : (
        <span aria-hidden className="inline-block w-3 h-2.5 bg-error" style={{ opacity: props.opacity }} />
      )}
      {props.label}
    </li>
  );
}
