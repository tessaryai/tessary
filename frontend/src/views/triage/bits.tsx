// SPDX-License-Identifier: Apache-2.0
import type { Case } from "../../api/types";
import { RCA_VERDICT_LABEL } from "../rcaLabels";

const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/**
 * Relative under an hour ("2m ago"), then an absolute "July 29 14:30". Hand-formatted so the shape does
 * not pick up a locale-dependent comma.
 */
export function timeAgo(iso: string): string {
  const d = new Date(iso);
  const mins = Math.max(1, Math.round((Date.now() - d.getTime()) / 60_000));
  if (mins < 60) return `${mins}m ago`;
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  return `${MONTHS[d.getMonth()]} ${d.getDate()} ${hh}:${mm}`;
}

/** IDs truncate the tail, never the head (constitution rule 5). */
export function truncateId(id: string, keep = 8): string {
  return id.length <= keep ? id : `${id.slice(0, keep)}…`;
}

/** The detector tag on a case's meta line: the classifiers' own label, so a case and its finding agree. */
export { detectorLabel } from "../classifiers/shared";

/**
 * The call site to show, or null. `__unattributed__` is the sentinel for turns nothing could be attributed
 * to, so the chip is dropped rather than translated.
 */
export function displayCallSite(callSiteId: string | null | undefined): string | null {
  if (!callSiteId || callSiteId === "__unattributed__") return null;
  return callSiteId;
}

/** The shared bordered-list chassis: a 1px row gap over the border colour draws the dividers. */
export function ListChassis({ children }: { children: React.ReactNode }) {
  return (
    <div
      className="flex flex-col overflow-hidden gap-0.25"
      style={{ background: "var(--color-border)", border: "1px solid var(--color-border)", borderRadius: "var(--radius-card)" }}
    >
      {children}
    </div>
  );
}

/** The `·` meta separator, always in --color-subtle. */
export function Dot() {
  return (
    <span aria-hidden="true" className="text-subtle">
      ·
    </span>
  );
}

/**
 * A case row's one coloured element: red needs someone, amber is explained and needs a decision, green is
 * closed. `analysed` marks whether an open case has had an RCA.
 */
export function StateDot({ state, analysed }: { state: string; analysed?: boolean }) {
  if (state === "resolved") {
    return <Dotted colour="var(--color-success)" label="Closed" />;
  }
  return analysed ? (
    <Dotted colour="var(--color-warning)" label="Open, analyzed" />
  ) : (
    <Dotted colour="var(--color-error)" label="Open" />
  );
}

function Dotted({ colour, label }: { colour: string; label: string }) {
  return (
    <span
      role="img"
      aria-label={label}
      title={label}
      className="inline-block shrink-0 rounded-pill"
      style={{ width: 8, height: 8, background: colour }}
    />
  );
}

/** What a finished RCA concluded, as one line: the run's summary as written, else the verdict's label. */
export function causeLine(c: Pick<Case, "cause" | "rca_verdict">): string | null {
  if (c.rca_verdict == null) return null;
  if (c.cause != null) return c.cause;
  const label = RCA_VERDICT_LABEL[c.rca_verdict];
  return label && c.rca_verdict !== "inconclusive" && c.rca_verdict !== "no_cause_found" ? label : "No cause located";
}
