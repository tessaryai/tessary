// SPDX-License-Identifier: Apache-2.0
/*
 * Classifiers: the findings, and what triage made of each one.
 *
 * <h2>Why there are no queues here any more</h2>
 * There used to be two: "Needs a decision" for anything nothing had ruled on, and "Needs review" for
 * anything a Layer-2 run returned `unclear` on. Both asked a person to be the fallback for a machine —
 * the first for one that had not run, the second for one that ran and shrugged — and between them they
 * grew without bound, because nothing about a finding sitting in either of them made it more decidable
 * tomorrow than it was today. That is the queue this whole redesign exists to remove.
 *
 * What replaced them: every finding that opens gets exactly one triage run, and that run ends in
 * exactly one of two acts. `positive` opens a case, which is where a person picks the work up. `negative`
 * and `unclear` both CLOSE the finding — the second is a bet that the cause has stopped, and the bet is
 * called by recurrence rather than by somebody reading a list. So this page is a record of what has
 * been decided, not a pile of what has not.
 *
 * <h2>The two sections</h2>
 * Open findings (pending, in flight, or sound and now a case) and closed history. The split is
 * `triage_action`, never `status`: closing is a triage act, and the row deliberately stays in the live
 * index so its cause can keep firing against it and drive the re-open.
 *
 * <h2>The title is the classifier's own sentence</h2>
 * There is deliberately no "reading" column restating the shift. Each detector writes its finding's
 * title itself and puts its own numbers in it ("search_docs is failing 3.1% of the time, up from
 * 0.4%"), because the detectors do not measure comparable things and a shared column would have to
 * flatten them into one that fits none. See {@link BehaviorFindingView#title} on the server.
 */
import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import type { BehaviorFinding } from "../../api/types";
import { useTenant } from "../../tenant/TenantContext";
import {
  ErrorNote,
  LoadingRow,
  PageHeader,
  Section,
  Table,
  TBody,
  TD,
  TH,
  THead,
  TR,
} from "../../ui";
import {
  CONTAINER,
  ago,
  detectorLabel,
  enabledDetectors,
  isBaselineFinding,
  isClosedByTriage,
  triageState,
} from "./shared";

export function ClassifiersPage() {
  const { api } = useTenant();
  const navigate = useNavigate();

  const classifiersQ = useQuery({ queryKey: ["classifiers", api.base], queryFn: api.listClassifiers });

  /**
   * The raw Layer-1 stream, ungated.
   *
   * <p>One call, where there used to be two. The second fetched the Layer-2-confirmed set so the page
   * could SUBTRACT it and show only what was outstanding; nothing is outstanding now, because every
   * finding carries its own ruling and the page's job is to show it. `include: "all"` is what makes a
   * closed finding visible at all — the default gate returns only what triage found sound.
   */
  const allQ = useQuery({
    queryKey: ["behavior-findings", api.base, "all"],
    queryFn: () => api.listBehaviorFindings("open", "all"),
  });

  /**
   * The open cases, read from the same cache the Triage nav badge already fills, so resolving a sound
   * finding to the case it opened costs no extra request. A case that has since been resolved is in
   * neither bucket, and its finding's row simply names the ruling without linking — the case is
   * history at that point, and Triage is where history is read.
   */
  const casesQ = useQuery({ queryKey: ["cases", api.base], queryFn: api.getTriage, retry: false });

  const classifiers = classifiersQ.data ?? [];
  const enabled = enabledDetectors(classifiers);

  /**
   * Both live buckets, because muting silences a case rather than closing it. A muted case is still
   * open and still at its own URL, so dropping it here would leave its finding reading `Sound` with
   * nowhere to go — indistinguishable from a finding whose case was resolved.
   */
  const caseByFinding = useMemo(() => {
    const map = new Map<string, string>();
    for (const c of [...(casesQ.data?.cases ?? []), ...(casesQ.data?.muted ?? [])]) {
      if (c.finding_id) map.set(c.finding_id, c.id);
    }
    return map;
  }, [casesQ.data]);

  const { live, closed } = useMemo(() => {
    const findings = allQ.data?.findings ?? [];
    return {
      live: findings.filter((f) => !isClosedByTriage(f)),
      closed: findings.filter(isClosedByTriage),
    };
  }, [allQ.data]);

  return (
    <div style={CONTAINER}>
      <PageHeader
        kicker="Monitor"
        title="Classifiers"
        actions={
          <Link
            to="detectors"
            className="inline-flex items-center h-[30px] px-3 rounded-control border border-border-strong text-small text-muted hover:text-fg transition-colors"
            style={{ transitionDuration: "var(--duration-micro)" }}
          >
            {classifiersQ.isSuccess
              ? `Catalog · ${enabled.length} of ${classifiers.length} on`
              : "Catalog"}
          </Link>
        }
      />

      {allQ.isLoading && <LoadingRow />}
      {allQ.isError && <ErrorNote error={allQ.error} />}
      {/*
        * The catalog only fills the header's count, but a failure to read it still has to say so:
        * silently falling back to `classifiers = []` would render "0 of 0 on" — a claim that nothing
        * is watching production, which is the opposite of "we could not find out". Hence the
        * `isSuccess` guard on the label above, and this note.
        */}
      {classifiersQ.isError && <ErrorNote error={classifiersQ.error} />}

      {allQ.data && live.length === 0 && closed.length === 0 && (
        <p className="text-body text-subtle" style={{ maxWidth: 520 }}>
          No findings. A classifier creates a finding when a whole population moves, not when one trace
          looks odd, so an empty page is the healthy state.
        </p>
      )}

      {live.length > 0 && (
        <Section title="Open" subtitle={openSubtitle(live)}>
          <FindingTable
            findings={live}
            caseByFinding={caseByFinding}
            onOpen={(id) => navigate(findingPath(id))}
          />
        </Section>
      )}

      {closed.length > 0 && (
        <Section
          title="Closed by triage"
          subtitle="Ruled a measurement artifact, or unsettled on the evidence. Each one re-opens by itself if its cause keeps firing."
        >
          <FindingTable
            findings={closed}
            caseByFinding={caseByFinding}
            onOpen={(id) => navigate(findingPath(id))}
          />
        </Section>
      )}

      {live.length > 0 && (
        <p className="text-small text-subtle">
          A finding triage rules sound opens a case in <Link to="../triage">Triage</Link>, which is
          where the work is picked up.
        </p>
      )}
    </div>
  );
}

/** What the open section is, counted by the one distinction that changes what you do next. */
function openSubtitle(findings: BehaviorFinding[]): string {
  const opened = findings.filter((f) => f.triageVerdict === "positive").length;
  const waiting = findings.length - opened;
  if (opened === 0) return waiting === 1 ? "One finding, awaiting triage." : `${waiting} findings, awaiting triage.`;
  if (waiting === 0) return opened === 1 ? "One case opened." : `${opened} cases opened.`;
  return `${opened} ${opened === 1 ? "case" : "cases"} opened · ${waiting} awaiting triage.`;
}

function findingPath(id: string): string {
  return `findings/${encodeURIComponent(id)}`;
}

/**
 * One row per cause, one line each.
 *
 * <p>Fixed row height is the point: a list is read by scanning down it, and rows that breathe
 * differently depending on how long a title happens to be cannot be scanned at all. A title too long
 * for its column truncates rather than wrapping: the whole sentence is one click away, on a page that
 * has room for it.
 *
 * <p>The triage column carries a verdict where the old queues carried a section heading, which is the
 * whole shape of the change: the ruling is now a fact about the row rather than the bucket it landed
 * in, so a page holding four different rulings reads as one list.
 */
function FindingTable({
  findings,
  caseByFinding,
  onOpen,
}: {
  findings: BehaviorFinding[];
  caseByFinding: Map<string, string>;
  onOpen: (id: string) => void;
}) {
  return (
    <Table>
      <THead>
        <TR>
          <TH>Finding</TH>
          <TH style={{ width: 150 }}>Classifier</TH>
          <TH style={{ width: 160 }}>Triage</TH>
          <TH style={{ width: 120 }}>First seen</TH>
          <TH style={{ width: 32 }} />
        </TR>
      </THead>
      <TBody>
        {findings.map((f) => (
          <TR key={f.id} interactive onClick={() => onOpen(f.id)}>
            <TD className="text-fg truncate" style={{ maxWidth: 0 }} title={f.title}>
              {f.title}
            </TD>
            {/*
              * The baseline tag is not decoration: a conformance row is one of two different claims —
              * "this rule has always been broken" and "this rule got worse" — and this list is where a
              * reader decides which to open first. The title says it in words; this says it where the
              * eye scans.
              */}
            <TD className="text-muted truncate">
              {f.detector ? detectorLabel(f.detector) : "–"}
              {isBaselineFinding(f) && <span className="text-subtle"> · baseline</span>}
            </TD>
            <TD>
              <TriageCell finding={f} caseId={caseByFinding.get(f.id)} />
            </TD>
            <TD className="text-subtle whitespace-nowrap" title={new Date(f.firstSeenAt).toLocaleString()}>
              {ago(f.firstSeenAt)}
            </TD>
            <TD className="text-subtle">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                <path
                  d="M4 2.5 7.5 6 4 9.5"
                  stroke="currentColor"
                  strokeWidth="1.25"
                  strokeLinecap="round"
                />
              </svg>
            </TD>
          </TR>
        ))}
      </TBody>
    </Table>
  );
}

/**
 * The ruling, and for a sound one the case it opened.
 *
 * <p>The link stops the row click rather than riding on it, because they go to two different places
 * that a reader means differently: the row is "show me the evidence", the link is "take me to the
 * work". Only `positive` ever gets one — nothing else opened a case to link to.
 */
function TriageCell({ finding, caseId }: { finding: BehaviorFinding; caseId: string | undefined }) {
  const state = triageState(finding);
  const tone =
    state.tone === "positive"
      ? "text-fg"
      : state.tone === "closed"
        ? "text-subtle"
        : state.tone === "failed"
          ? "text-error"
          : "text-muted";
  return (
    <span className={`${tone} whitespace-nowrap`}>
      {state.label}
      {state.tone === "positive" && caseId && (
        <>
          {" · "}
          <Link
            to={`../cases/${encodeURIComponent(caseId)}`}
            onClick={(e) => e.stopPropagation()}
            className="text-link hover:text-link-hover">
            Open case
          </Link>
        </>
      )}
    </span>
  );
}
