// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.DossierPayload;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The RCA pipeline, anchored on a FINDING: (1) read the finding's CLAIM — {@link FindingClaim}, which is
 * the claim and nothing anybody ruled about it; (2) read its {@code finding_evidence} rows for what the
 * run may cite, whether there is a {@code baseline} side, and how many rows were flagged; (3) hand the
 * claim, its numbers, the classifier's RCA method and the MCP tools to a sandboxed agent ({@link
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
 * {@code triage_*} column in it, so there is no ruling in scope to leak. The prompt says the finding is
 * confirmed and never says by whom.
 *
 * <h2>Evidence by reference, not by copy</h2>
 *
 * <p>The dossier is what is finding-specific — the claim and the detector's own numbers — and the
 * substrate is read on demand. The evidence refs are uncapped at write time so the agent decides at
 * read time what to open: it pages {@code get_finding_evidence}, reads what it chooses with
 * {@code get_trace}, and states what it took.
 */
@Service
public class RcaAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RcaAnalysisService.class);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final RcaReportRepository reports;
    private final AgenticRcaEngine agenticEngine;
    private final ObjectMapper mapper;

    public RcaAnalysisService(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            RcaReportRepository reports,
            AgenticRcaEngine agenticEngine,
            ObjectMapper mapper) {
        this.findings = findings;
        this.evidence = evidence;
        this.reports = reports;
        this.agenticEngine = agenticEngine;
        this.mapper = mapper;
    }

    public void analyze(RcaJobRow job) {
        RcaReportRow report = reports.findByJobId(job.projectId(), job.id())
                .orElseThrow(() -> new IllegalStateException("rca job " + job.id() + " has no report row"));
        FindingClaim finding = findings.findClaim(job.projectId(), job.findingId())
                .orElseThrow(() -> new TessaryException(RcaError.SUBJECT_NOT_FOUND, job.findingId()));

        AgenticRcaEngine.Evidence ev = evidence(job.projectId(), finding.id());
        if (ev.citableTraceIds().isEmpty() && ev.citableSessionIds().isEmpty()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, finding.id());
        }

        AgenticRcaEngine.Result result =
                agenticEngine.run(job, report, finding.id(), dossierFiles(report, finding), ev);
        complete(
                job,
                result.verdict(),
                result.summary(),
                result.ruledOut(),
                result.causes(),
                result.detailedReport(),
                result.repoAvailable());
    }

    /**
     * Roles that name the flagged population when a classifier draws no narrower subset. {@code member}
     * is the population itself; {@code exemplar} and {@code changepoint} are picks from within it, and
     * are included so a classifier that writes them and nothing else is not read as having flagged
     * nothing at all.
     */
    private static final Set<String> FLAGGED_WHEN_NO_WITNESS = Set.of(
            FindingEvidenceRow.Role.MEMBER, FindingEvidenceRow.Role.EXEMPLAR, FindingEvidenceRow.Role.CHANGEPOINT);

    /**
     * What the run needs from the finding's evidence rows.
     *
     * <p>The flagged rows are the narrowest set the classifier drew: {@code witness} rows where it wrote any, since
     * a witness is a member the detector singled out (tool error's members are every call, its witnesses the
     * failing ones), and the population roles otherwise. A cause may cite a flagged row or a {@code baseline} row,
     * never a member the detector did not flag: it would read as explained when it is healthy. Session-grain refs
     * (no trace id) are counted the same way; only frustration writes them, and its grain is sessions.
     */
    private AgenticRcaEngine.Evidence evidence(String projectId, String findingId) {
        Set<String> baseline = new LinkedHashSet<>();
        Set<String> witnessTraces = new LinkedHashSet<>();
        Set<String> populationTraces = new LinkedHashSet<>();
        // Sessions named by a session-grain ref (no trace id) decide the grain; any session a flagged row names
        // is citable.
        Set<String> witnessSessionRefs = new LinkedHashSet<>();
        Set<String> populationSessionRefs = new LinkedHashSet<>();
        Set<String> witnessSessions = new LinkedHashSet<>();
        Set<String> populationSessions = new LinkedHashSet<>();
        for (FindingEvidenceRow row : evidence.listByFinding(projectId, findingId)) {
            if (FindingEvidenceRow.Role.BASELINE.equals(row.role())) {
                if (row.traceId() != null) baseline.add(row.traceId());
                continue;
            }
            boolean witness = FindingEvidenceRow.Role.WITNESS.equals(row.role());
            if (!witness && !FLAGGED_WHEN_NO_WITNESS.contains(row.role())) continue;
            if (row.traceId() != null) {
                (witness ? witnessTraces : populationTraces).add(row.traceId());
            } else if (row.sessionId() != null) {
                (witness ? witnessSessionRefs : populationSessionRefs).add(row.sessionId());
            }
            if (row.sessionId() != null) (witness ? witnessSessions : populationSessions).add(row.sessionId());
        }
        boolean anyWitness = !witnessTraces.isEmpty() || !witnessSessionRefs.isEmpty();
        Set<String> flaggedTraces = anyWitness ? witnessTraces : populationTraces;
        Set<String> flaggedSessionRefs = anyWitness ? witnessSessionRefs : populationSessionRefs;
        Set<String> citableSessions = anyWitness ? witnessSessions : populationSessions;
        // A trace cited on both sides is flagged: it is what the claim is about, not a comparison anchor.
        baseline.removeAll(flaggedTraces);
        Set<String> citableTraces = new LinkedHashSet<>(flaggedTraces);
        citableTraces.addAll(baseline);
        boolean sessionGrain = !flaggedSessionRefs.isEmpty();
        return new AgenticRcaEngine.Evidence(
                citableTraces,
                citableSessions,
                !baseline.isEmpty(),
                sessionGrain ? flaggedSessionRefs.size() : flaggedTraces.size(),
                sessionGrain ? "sessions" : "traces");
    }

    private void complete(
            RcaJobRow job,
            String verdict,
            @Nullable String summary,
            List<RuledOutCheck> ruledOut,
            List<Cause> causes,
            @Nullable String detailedReport,
            boolean repoAvailable) {
        reports.complete(
                job.id(),
                "done",
                verdict,
                summary,
                writeJson(ruledOut),
                causes.isEmpty() ? null : writeJson(causes),
                detailedReport,
                repoAvailable);
        log.info(
                "rca done project={} subject={}:{} metric={} verdict={} causes={}",
                job.projectId(),
                job.subjectKind(),
                job.subjectId(),
                job.metric(),
                verdict,
                causes.size());
    }

    // ---- dossier assembly ---------------------------------------------------------------------------

    /**
     * The dossier as sandbox files (relative path → content): the claim, the detector's own numbers
     * verbatim, the classifier's RCA method when it has one, and the MCP tools. Files rather than one long
     * prompt because an agent that can open, re-read and quote a file reasons over it better than one
     * handed a wall of JSON — and the numbers stay verbatim instead of being paraphrased into prose.
     *
     * <p>The layout is the contract {@link AgenticRcaEngine}'s prompt describes; change them together.
     */
    private Map<String, String> dossierFiles(RcaReportRow report, FindingClaim finding) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("finding.md", findingDoc(report, finding));
        // A dedicated per-shape assembler writes markdown when this classifier's payload matches one (see
        // ClassifierDossierAssembler's dispatch-by-shape note); any other payload is the stripped JSON, fenced,
        // so the file is markdown under one name for every classifier.
        String payload = ClassifierDossierAssembler.assemble(
                        mapper,
                        evidence,
                        finding.projectId(),
                        finding.id(),
                        evidenceCounts(finding),
                        finding.payloadJson())
                .orElseGet(() -> fenced(DossierPayload.forAgent(mapper, finding.payloadJson())));
        if (payload != null && !payload.isBlank()) {
            files.put(EVIDENCE_FILE, payload);
        }
        String method = AgenticRcaEngine.method(finding.classifierKey());
        if (method != null) files.put(AgenticRcaEngine.METHOD_FILE, method);
        files.put(AgenticRcaEngine.TOOLS_FILE, AgenticRcaEngine.TOOLS);
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

    static final String EVIDENCE_FILE = "evidence.md";

    /** A raw payload as markdown. The fence is longer than any backtick run in the JSON, so a string value that
     *  holds a code block cannot close it early. */
    private static @Nullable String fenced(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        int longest = 0;
        for (var m = BACKTICKS.matcher(json); m.find(); )
            longest = Math.max(longest, m.group().length());
        String fence = "`".repeat(Math.max(3, longest + 1));
        return "# Classifier evidence\n\n" + fence + "json\n" + json.strip() + "\n" + fence + "\n";
    }

    private static final Pattern BACKTICKS = Pattern.compile("`+");

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
                + " retention, not a lost write. Refs whose role is `baseline` are the BEFORE side;"
                + " `method.md`, when present, says what each other role holds.\n");
        return sb.toString();
    }

    private String writeJson(Object value) {
        return mapper.valueToTree(value).toString();
    }
}
