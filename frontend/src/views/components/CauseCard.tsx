// SPDX-License-Identifier: Apache-2.0
import { Link } from "react-router-dom";
import type { RcaCause } from "../../api/types";
import { Button, Card } from "../../ui";
import { causeLabels, isCurrentCause, type CauseKind } from "../rcaLabels";

/**
 * One cause an RCA found, the same card for every case type.
 *
 * <p>The prose rows are written by the agent for a reader with no context; the labels beside them are not,
 * they follow from the cause and the case type. Evidence is only ever the id chips and the repository line:
 * the full investigation lives on the report page.
 */
export function CauseCard({
  cause,
  kind,
  basePath,
  affected,
  show,
  repoRead = true,
}: {
  cause: RcaCause;
  kind: CauseKind;
  basePath: string;
  /** What `affected_count` counts, singular and plural. */
  affected: [singular: string, plural: string];
  /** Filters the list below the card to this cause's sessions or answers. */
  show?: { count: number; unit: [singular: string, plural: string]; onShow: () => void };
  /** False when the run had no repository: nothing it says about a prompt or code line was checked. */
  repoRead?: boolean;
}) {
  const current = isCurrentCause(cause);
  const labels = causeLabels(kind, cause);
  const rows: [label: string, text: string | null][] = [
    [labels.change, cause.what_changed],
    [labels.how, cause.how_it_caused_this],
    [labels.next, cause.next_step],
  ];
  const where = repoRead && cause.attribution && cause.attribution.kind !== "unknown" ? cause.attribution : null;
  const n = cause.affected_count;
  // Where the page has no list to filter, the sessions are links of their own.
  const sessions = show ? [] : cause.evidence_session_ids;

  return (
    <Card className="border border-border flex flex-col gap-4 p-5">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h3 className="m-0 min-w-0 flex-1 text-body font-medium text-fg">{cause.title}</h3>
        {(current || cause.confidence !== "high") && <Confidence level={cause.confidence} current={current} />}
        {n > 0 && (
          <span className="text-small text-muted whitespace-nowrap">
            {n.toLocaleString()} {n === 1 ? affected[0] : affected[1]}
          </span>
        )}
      </div>

      <dl className="m-0 grid gap-x-6 gap-y-3.5" style={{ gridTemplateColumns: "minmax(0, 200px) minmax(0, 1fr)" }}>
        {rows
          .filter((r): r is [string, string] => r[1] != null && r[1] !== "")
          .map(([label, text]) => (
            <Row key={label} label={label}>
              <p className="m-0 text-body text-fg">{text}</p>
            </Row>
          ))}
      </dl>

      {(where || sessions.length > 0 || cause.evidence_trace_ids.length > 0) && (
        <dl
          className="m-0 grid gap-x-6 gap-y-3 border-t border-border pt-4"
          style={{ gridTemplateColumns: "minmax(0, 200px) minmax(0, 1fr)" }}
        >
          {where && (where.path || where.excerpt) && (
            <Row label={whereLabel(current ? cause.type : where.kind)}>
              <div className="flex min-w-0 flex-col gap-2">
                {where.path && (
                  <p className="m-0 font-mono text-small text-fg wrap-anywhere">
                    {where.commit ? `${where.path} @ ${where.commit.slice(0, 7)}` : where.path}
                  </p>
                )}
                {where.excerpt && (
                  <pre className="m-0 rounded-control border border-border bg-raised py-2.5 px-3 font-mono text-code text-fg-secondary whitespace-pre-wrap wrap-anywhere">
                    {where.excerpt}
                  </pre>
                )}
              </div>
            </Row>
          )}
          {sessions.length > 0 && (
            <Row label="Sessions">
              <Chips ids={sessions} href={(id) => `${basePath}/sessions/${encodeURIComponent(id)}`} />
            </Row>
          )}
          {cause.evidence_trace_ids.length > 0 && (
            <Row label="Evidence">
              <Chips ids={cause.evidence_trace_ids} href={(id) => `${basePath}/traces/${encodeURIComponent(id)}`} />
            </Row>
          )}
        </dl>
      )}

      {show && show.count > 0 && (
        <div>
          <Button size="sm" variant="secondary" onClick={show.onShow}>
            Show {show.count} {show.count === 1 ? show.unit[0] : show.unit[1]}
          </Button>
        </div>
      )}
    </Card>
  );
}

function Chips({ ids, href }: { ids: string[]; href: (id: string) => string }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      {ids.map((id) => (
        <Link
          key={id}
          to={href(id)}
          title={id}
          className="rounded-control border border-border-strong font-mono text-link hover:text-link-hover transition-colors py-0.5 px-1.75 text-label"
          style={{ transitionDuration: "var(--duration-micro)" }}
        >
          {id.length <= 8 ? id : `${id.slice(0, 8)}…`}
        </Link>
      ))}
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="font-mono uppercase text-label text-muted pt-0.5">{label}</dt>
      <dd className="m-0 min-w-0">{children}</dd>
    </>
  );
}

/** What the repository line is, by the cause's type or, on an older cause, its attribution's kind. */
function whereLabel(kind: string | null): string {
  switch (kind) {
    case "prompt":
      return "Prompt";
    case "model":
      return "Model";
    case "tool":
      return "Tool";
    case "data":
      return "Data";
    case "code":
    case "unknown":
    case null:
      return "Code";
    default:
      return "Where";
  }
}

/**
 * How sure a cause is. Every current cause carries its level. On an older report only a lead does: a proven
 * cause there carries no badge, because being on the page as a cause says it.
 */
function Confidence({ level, current }: { level: string; current: boolean }) {
  const high = level === "high";
  const medium = level === "medium";
  return (
    <span
      className="font-mono uppercase text-label rounded-control px-1.5 py-0.5"
      style={{
        color: high ? "var(--color-fg)" : medium ? "var(--color-warning)" : "var(--color-subtle)",
        background: high ? "var(--color-accent-subtle)" : medium ? "var(--color-warning-subtle)" : "var(--color-raised)",
      }}
    >
      {current ? (high ? "High" : "Medium") : level}
    </span>
  );
}
