// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.catalog.ClassifierMethodCard;
import ai.tessary.classifier.finding.DossierPayload;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaChecklist.Measurement;
import ai.tessary.rca.RcaDtos.Hypothesis;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.rca.RcaSynthesisOutput.ChecklistAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The RCA pipeline, anchored on a FINDING: (1) read the finding's CLAIM — {@link FindingClaim}, which is
 * the claim and nothing anybody ruled about it; (2) dereference its {@code finding_evidence} rows into
 * two sides, the {@code baseline}-role references and the flagged ones; (3) measure the structural
 * checklist across them ({@link RcaChecklist} — grader versions, serving models, traffic mix, grading
 * health); (4) hand the claim, its numbers and that checklist to a sandboxed agent ({@link
 * AgenticRcaEngine}) which reads every row it cites through MCP, with the project repo cloned beside it
 * when there is one. The result is stamped onto the {@code rca_report} row.
 *
 * <h2>The context firewall</h2>
 *
 * <p><b>Nothing Layer 2 concluded crosses into this run.</b> A person may read the triage ruling, its
 * summary and its check scripts and then press "Run RCA"; the agent may not. Layer 2 runs a cheap model
 * and its mistakes must not arrive here as premises — the failure to avoid is an RCA that confirms a
 * triage error in more words and with more authority. The enforcement is structural rather than
 * conventional: this class reads {@link FindingRepository#findClaim}, whose SELECT list has no
 * {@code triage_*} column in it, so there is no ruling in scope to leak. The prompt does not mention
 * that a triage pass exists either, because "an earlier pass thought this was real" is itself a prior.
 *
 * <p>The consequence is deliberate: "nothing happened here" is a supported RCA conclusion. RCA is the
 * only check on the gate Layer 2 keeps, and a run told the claim had already been validated could not
 * perform that check.
 *
 * <h2>Evidence by reference, not by copy</h2>
 *
 * <p>The dossier is what is finding-specific — the claim, the detector's own numbers, the measured
 * checklist — and the substrate is read on demand. It used to ship hydrated traces and an exhaustive
 * per-side verdict ledger, which meant this class chose the sample the agent reasoned over before
 * knowing the question, and capped it at whatever fitted. The evidence refs are uncapped at write time
 * precisely so that decision can be made at read time by whoever has to defend it: the agent pages
 * {@code get_finding_evidence}, reads what it chooses with {@code get_trace}, and states what it took.
 *
 * <p>Nothing here short-circuits. The checklist measures and never judges: the thresholds that used to
 * end the run early ("any grader-version delta is a redefinition", "no failing verdicts means
 * inconclusive") produced confident wrong answers on tracing and grading hiccups, so the judgment moved
 * to the agent, which can check the repo before committing to a cause.
 */
@Service
public class RcaAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RcaAnalysisService.class);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final RcaChecklist checklist;
    private final RcaReportRepository reports;
    private final AgenticRcaEngine agenticEngine;
    private final ObjectMapper mapper;

    public RcaAnalysisService(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            RcaChecklist checklist,
            RcaReportRepository reports,
            AgenticRcaEngine agenticEngine,
            ObjectMapper mapper) {
        this.findings = findings;
        this.evidence = evidence;
        this.checklist = checklist;
        this.reports = reports;
        this.agenticEngine = agenticEngine;
        this.mapper = mapper;
    }

    public void analyze(RcaJobRow job) {
        RcaReportRow report = reports.findByJobId(job.projectId(), job.id())
                .orElseThrow(() -> new IllegalStateException("rca job " + job.id() + " has no report row"));
        FindingClaim finding = findings.findClaim(job.projectId(), job.findingId())
                .orElseThrow(() -> new TessaryException(RcaError.SUBJECT_NOT_FOUND, job.findingId()));

        // ---- 1. dereference the evidence into the two sides ------------------------------------
        Sides sides = sides(job.projectId(), finding.id());
        if (sides.flagged().isEmpty() && sides.baseline().isEmpty()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, finding.id());
        }

        // ---- 2. measure the structural checklist (no thresholds, no judgment) -------------------
        List<Measurement> measurements =
                new ArrayList<>(checklist.measure(job.projectId(), sides.baseline(), sides.flagged()));
        measurements.add(checklist.failingCohortShape(job.projectId(), new LinkedHashSet<>(sides.flagged())));

        // Citable = every trace the finding cites, per side. The evidence rows ARE the citable set:
        // there is nothing beyond them the agent could legitimately point at as this finding's evidence.
        Set<String> baselineTraceIds = new LinkedHashSet<>(sides.baseline());
        Set<String> flaggedTraceIds = new LinkedHashSet<>(sides.flagged());

        // ---- 3. the sandboxed agent investigates -------------------------------------------------
        Map<String, String> files = dossierFiles(report, finding, measurements);
        AgenticRcaEngine.Result result = agenticEngine.run(
                job, report, finding.id(), files, baselineTraceIds, flaggedTraceIds, measuredChecks(measurements));
        complete(
                job,
                result.verdict(),
                result.summary(),
                merge(measurements, result.checklist()),
                result.hypotheses(),
                result.detailedReport(),
                result.repoAvailable());
    }

    /**
     * The finding's evidence, split into the two sides the checklist measures across.
     *
     * <p>{@code baseline} is what the classifier compared against. {@code flagged} is what it is
     * complaining about — and that is NOT simply "everything that is not baseline", which is what this
     * used to be.
     *
     * <p><b>Why the distinction started to matter.</b> {@code tool_error} now writes both halves of its
     * fraction: {@code member} is every call in the spell, healthy ones included, and {@code witness} is
     * the failing subset. Lumping them together made {@code flagged} the DENOMINATOR — so
     * {@code failingCohortShape}, a check whose entire job is to describe what the failures have in
     * common, was handed the whole population and reported the shape of ordinary traffic. Every
     * classifier that writes no {@code witness} is unaffected: for those, {@code member} IS the flagged
     * population and the behaviour is exactly as before.
     */
    private record Sides(List<String> baseline, List<String> flagged) {}

    /**
     * Roles that name the flagged population when a classifier draws no narrower subset. {@code member}
     * is the population itself; {@code exemplar} and {@code changepoint} are picks from within it, and
     * are included so a classifier that writes them and nothing else is not read as having flagged
     * nothing at all.
     */
    private static final Set<String> FLAGGED_WHEN_NO_WITNESS = Set.of(
            FindingEvidenceRow.Role.MEMBER, FindingEvidenceRow.Role.EXEMPLAR, FindingEvidenceRow.Role.CHANGEPOINT);

    private Sides sides(String projectId, String findingId) {
        Set<String> baseline = new LinkedHashSet<>();
        Set<String> witnesses = new LinkedHashSet<>();
        Set<String> population = new LinkedHashSet<>();
        for (FindingEvidenceRow row : evidence.listByFinding(projectId, findingId)) {
            // Span-grain rows carry their trace id too, so nothing is dropped by keying on it here — but
            // the precision IS lost, because the checklist's reads are all trace-scoped. A trace holding
            // fifty calls of which one failed counts once, which is what "what do the failing traces have
            // in common" wants; a per-call breakdown would need a different query shape than this asks for.
            String traceId = row.traceId();
            if (traceId == null) continue;
            if (FindingEvidenceRow.Role.BASELINE.equals(row.role())) {
                baseline.add(traceId);
            } else if (FindingEvidenceRow.Role.WITNESS.equals(row.role())) {
                witnesses.add(traceId);
            } else if (FLAGGED_WHEN_NO_WITNESS.contains(row.role())) {
                population.add(traceId);
            }
        }
        // The narrowest set the classifier drew. A witness is a member the detector singled out, so where
        // both exist the witnesses are the claim and the members are what it was a fraction OF.
        Set<String> flagged = witnesses.isEmpty() ? population : witnesses;
        // A trace cited on both sides is flagged: it is what the claim is about, and offering it as a
        // baseline anchor as well would let the agent cite the same trace as both sides of a comparison.
        baseline.removeAll(flagged);
        return new Sides(List.copyOf(baseline), List.copyOf(flagged));
    }

    /** Fold the agent's assessments back onto the measurements they judged, in measurement order —
     *  a check the agent skipped still reaches the report, carrying its numbers and no verdict. */
    private static List<RuledOutCheck> merge(List<Measurement> measurements, List<ChecklistAssessment> assessments) {
        Map<String, ChecklistAssessment> byCheck = new LinkedHashMap<>();
        for (ChecklistAssessment a : assessments) byCheck.put(a.check(), a);
        List<RuledOutCheck> out = new ArrayList<>();
        for (Measurement m : measurements) {
            ChecklistAssessment a = byCheck.get(m.check());
            out.add(
                    a == null
                            ? RuledOutCheck.unassessed(m.check(), m.finding())
                            : RuledOutCheck.assessed(m.check(), a.assessment(), a.detail(), m.finding()));
        }
        return out;
    }

    private static Set<String> measuredChecks(List<Measurement> measurements) {
        return measurements.stream().map(Measurement::check).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void complete(
            RcaJobRow job,
            String verdict,
            String summary,
            List<RuledOutCheck> checks,
            List<Hypothesis> hypotheses,
            @Nullable String detailedReport,
            boolean repoAvailable) {
        reports.complete(
                job.id(),
                "done",
                verdict,
                summary,
                writeJson(checks),
                writeJson(hypotheses),
                detailedReport,
                repoAvailable);
        log.info(
                "rca done project={} subject={}:{} metric={} verdict={} hypotheses={}",
                job.projectId(),
                job.subjectKind(),
                job.subjectId(),
                job.metric(),
                verdict,
                hypotheses.size());
    }

    // ---- dossier assembly ---------------------------------------------------------------------------

    /**
     * The dossier as sandbox files (relative path → content): the claim, the detector's own numbers
     * verbatim, and the measured checklist. Files rather than one long prompt because an agent that can
     * open, re-read and quote a file reasons over it better than one handed a wall of JSON — and the
     * numbers stay verbatim instead of being paraphrased into prose on the way in.
     *
     * <p>The layout is the contract {@link AgenticRcaEngine}'s prompt describes; change them together.
     */
    private Map<String, String> dossierFiles(RcaReportRow report, FindingClaim finding, List<Measurement> checks) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("finding.md", findingDoc(report, finding));
        // How the classifier that wrote this claim works, and what each evidence role means for IT.
        // Without it the agent reads one role vocabulary as though it meant the same thing everywhere,
        // and an absent `baseline` — correct for three of the five detectors — reads as a lost write.
        String method = ClassifierMethodCard.forClassifier(finding.classifierKey());
        if (method != null) files.put("method.md", method);
        // A dedicated per-shape assembler when this classifier's payload matches one (see
        // ClassifierDossierAssembler's dispatch-by-shape note) — the same one BehaviorTriageEngine.
        // dossier() uses, so triage and RCA never disagree about what a classifier's evidence looks
        // like. DossierPayload.forAgent's strip-only pass is still the fallback for everything else.
        String payload = ClassifierDossierAssembler.assemble(
                        mapper,
                        evidence,
                        finding.projectId(),
                        finding.id(),
                        evidenceCounts(finding),
                        finding.payloadJson())
                .orElseGet(() -> DossierPayload.forAgent(mapper, finding.payloadJson()));
        if (payload != null && !payload.isBlank()) {
            files.put("evidence.json", payload);
        }
        files.put("checklist.md", checklistDoc(checks));
        return files;
    }

    /** {@link FindingClaim#evidenceCount} for every role, read once for the dossier assembler — see
     *  {@code BehaviorTriageEngine}'s identically-named helper, kept as two copies because the two
     *  callers read the count off two different DTOs ({@code FindingClaim} vs {@code FindingRow}). */
    private static ClassifierDossierAssembler.EvidenceCounts evidenceCounts(FindingClaim finding) {
        return new ClassifierDossierAssembler.EvidenceCounts(
                finding.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR),
                finding.evidenceCount(FindingEvidenceRow.Role.MEMBER),
                finding.evidenceCount(FindingEvidenceRow.Role.BASELINE),
                finding.evidenceCount(FindingEvidenceRow.Role.WITNESS),
                finding.evidenceCount(FindingEvidenceRow.Role.CHANGEPOINT));
    }

    /** {@code dossier/finding.md} — what the classifier claims, over what, and how many refs it recorded
     *  per role, which is what says whether the evidence can be read whole or has to be sampled. */
    private static String findingDoc(RcaReportRow report, FindingClaim finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("# The finding\n\n");
        sb.append("- finding id: `")
                .append(finding.id())
                .append("` — the id every `get_finding_evidence` call takes\n");
        sb.append("- classifier: `").append(finding.classifierKey()).append("`\n");
        sb.append("- subject: ")
                .append(finding.subjectKind())
                .append(" '")
                .append(report.subjectLabel())
                .append("'\n");
        sb.append("- cause: `").append(finding.nativeCauseKey()).append("`\n");
        if (finding.callSiteId() != null) {
            sb.append("- call site: `").append(finding.callSiteId()).append("`\n");
        }
        if (finding.title() != null) {
            sb.append("- title: ").append(finding.title()).append('\n');
        }
        if (finding.basis() != null) {
            sb.append("- basis: ").append(finding.basis()).append('\n');
        }
        sb.append(String.format(
                Locale.ROOT,
                "- observed over %d sample(s), first at %s, most recently at %s\n",
                finding.sampleCount(),
                finding.onsetAt(),
                finding.lastSeenAt()));
        sb.append("\n## The evidence the detector recorded\n\n");
        for (String role : FindingEvidenceRow.Role.ALL) {
            sb.append("- `")
                    .append(role)
                    .append("`: ")
                    .append(finding.evidenceCount(role))
                    .append(" ref(s)\n");
        }
        sb.append("\nThese are the counts written at finding-open. `get_finding_evidence(count_only=true)`"
                + " gives them again beside what still survives in the substrate; a live count BELOW these is"
                + " retention, not a lost write. Refs whose role is `baseline` are the BEFORE side; every"
                + " other role is what the detector flagged.\n");
        return sb.toString();
    }

    /** {@code dossier/checklist.md} — the measurements the agent must assess, one section per check
     *  id. The ids here are what its {@code checklist} output is matched against. */
    private static String checklistDoc(List<Measurement> measurements) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Checklist — measurements, not verdicts\n\n");
        sb.append("Each section below is a structural cause the movement has to be read against. They are"
                + " measured, NOT judged: no thresholds were applied, and none of them has been ruled in"
                + " or out. Assess each one yourself and return exactly one entry per check id.\n");
        for (Measurement m : measurements) {
            sb.append("\n## ").append(m.check()).append('\n');
            sb.append(m.finding()).append('\n');
        }
        return sb.toString();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize rca report field", e);
        }
    }
}
