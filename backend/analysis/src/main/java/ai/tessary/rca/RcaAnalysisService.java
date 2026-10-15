// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.finding.DossierPayload;
import ai.tessary.classifier.finding.FindingAdapters;
import ai.tessary.classifier.finding.FindingClaim;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRepository;
import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler;
import ai.tessary.classifier.finding.dossier.EvidenceContext;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The context firewall: nothing Layer 2 concluded crosses into this run, so a cheap model's mistakes cannot arrive as
 * premises. Enforced structurally: {@link FindingRepository#findClaim} selects no {@code triage_*} column. Evidence is
 * by reference; the agent pages and reads what it chooses through MCP.
 */
@Service
public class RcaAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RcaAnalysisService.class);

    private final FindingRepository findings;
    private final FindingEvidenceRepository evidence;
    private final RcaReportRepository reports;
    private final AgenticRcaEngine agenticEngine;
    private final ObjectMapper mapper;
    private final FindingAdapters adapters;

    public RcaAnalysisService(
            FindingRepository findings,
            FindingEvidenceRepository evidence,
            RcaReportRepository reports,
            AgenticRcaEngine agenticEngine,
            ObjectMapper mapper,
            FindingAdapters adapters) {
        this.findings = findings;
        this.evidence = evidence;
        this.reports = reports;
        this.agenticEngine = agenticEngine;
        this.mapper = mapper;
        this.adapters = adapters;
    }

    public void analyze(RcaJobRow job) {
        RcaReportRow report = reports.findByJobId(job.projectId(), job.id())
                .orElseThrow(() -> new IllegalStateException("rca job " + job.id() + " has no report row"));
        List<FindingClaim> subject = subject(job.projectId(), job.findingId());

        AgenticRcaEngine.Evidence ev =
                evidence(job.projectId(), subject.stream().map(FindingClaim::id).toList());
        if (ev.citableTraceIds().isEmpty() && ev.citableSessionIds().isEmpty()) {
            throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, job.findingId());
        }

        AgenticRcaEngine.Result result = agenticEngine.run(
                job,
                report,
                new AgenticRcaEngine.Subject(
                        subject.stream().map(FindingClaim::id).toList(),
                        subject.get(0).onsetAt(),
                        subject.get(subject.size() - 1).lastSeenAt()),
                dossierFiles(report, subject),
                ev);
        complete(
                job,
                result.verdict(),
                result.summary(),
                result.ruledOut(),
                result.causes(),
                result.detailedReport(),
                result.repoAvailable());
    }

    /** The case is locked when RCA is pressed, so this set is the one the person saw. */
    private List<FindingClaim> subject(String projectId, String findingId) {
        List<String> ids = findings.caseSiblingIds(projectId, findingId);
        List<FindingClaim> claims = new ArrayList<>();
        for (String id : ids.isEmpty() ? List.of(findingId) : ids) {
            findings.findClaim(projectId, id).ifPresent(claims::add);
        }
        if (claims.isEmpty()) throw new TessaryException(RcaError.SUBJECT_NOT_FOUND, findingId);
        return claims;
    }

    /**
     * {@code exemplar} and {@code changepoint} are included so a classifier that writes only those is not read as
     * flagging nothing.
     */
    private static final Set<String> FLAGGED_WHEN_NO_WITNESS = Set.of(
            FindingEvidenceRow.Role.MEMBER, FindingEvidenceRow.Role.EXEMPLAR, FindingEvidenceRow.Role.CHANGEPOINT);

    /**
     * A cause may cite a flagged row or a {@code baseline} row, never an unflagged member: it would read as explained
     * when it is healthy.
     */
    private AgenticRcaEngine.Evidence evidence(String projectId, List<String> findingIds) {
        Set<String> baseline = new LinkedHashSet<>();
        Set<String> witnessTraces = new LinkedHashSet<>();
        Set<String> populationTraces = new LinkedHashSet<>();
        Set<String> witnessSessionRefs = new LinkedHashSet<>();
        Set<String> populationSessionRefs = new LinkedHashSet<>();
        Set<String> witnessSessions = new LinkedHashSet<>();
        Set<String> populationSessions = new LinkedHashSet<>();
        List<FindingEvidenceRow> rows = new ArrayList<>();
        for (String findingId : findingIds) rows.addAll(evidence.listByFinding(projectId, findingId));
        for (FindingEvidenceRow row : rows) {
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
        // A trace cited on both sides is flagged: it is what the claim is about.
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

    /**
     * Files rather than one prompt, so numbers stay verbatim. The layout is the contract {@link AgenticRcaEngine}'s
     * prompt describes; change them together.
     */
    private Map<String, String> dossierFiles(RcaReportRow report, List<FindingClaim> subject) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < subject.size(); i++) {
            FindingClaim finding = subject.get(i);
            String folder = findingFolder(i, finding);
            files.put(folder + "finding.md", findingDoc(report, finding));
            // An adapter's file first, then a per-shape assembler, else fenced JSON.
            String payload = ownersEvidence(finding)
                    .map(ClassifierDossierAssembler::budget)
                    .or(() -> ClassifierDossierAssembler.assemble(
                            mapper,
                            evidence,
                            finding.projectId(),
                            finding.id(),
                            evidenceCounts(finding),
                            finding.payloadJson()))
                    .orElseGet(() -> fenced(DossierPayload.forAgent(mapper, finding.payloadJson())));
            if (payload != null && !payload.isBlank()) {
                files.put(folder + EVIDENCE_FILE, payload);
            }
        }
        FindingClaim first = subject.get(0);
        String method = AgenticRcaEngine.method(first.classifierKey());
        if (method == null) method = adapters.rcaMethod(first).orElse(null);
        if (method != null) files.put(AgenticRcaEngine.METHOD_FILE, method);
        files.put(AgenticRcaEngine.TOOLS_FILE, AgenticRcaEngine.TOOLS);
        return files;
    }

    private Optional<String> ownersEvidence(FindingClaim finding) {
        if (adapters.ownerOf(finding).isEmpty()) return Optional.empty();
        ClassifierDossierAssembler.EvidenceCounts counts = evidenceCounts(finding);
        String enumeration =
                ClassifierDossierAssembler.enumeration(evidence, finding.projectId(), finding.id(), counts);
        return adapters.evidence(finding, new EvidenceContext(counts, enumeration));
    }

    /** Kept as two copies because the callers read the count off different DTOs. */
    private static ClassifierDossierAssembler.EvidenceCounts evidenceCounts(FindingClaim finding) {
        return new ClassifierDossierAssembler.EvidenceCounts(
                finding.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR),
                finding.evidenceCount(FindingEvidenceRow.Role.MEMBER),
                finding.evidenceCount(FindingEvidenceRow.Role.BASELINE),
                finding.evidenceCount(FindingEvidenceRow.Role.WITNESS),
                finding.evidenceCount(FindingEvidenceRow.Role.CHANGEPOINT));
    }

    static final String EVIDENCE_FILE = "evidence.md";

    static String findingFolder(int index, FindingClaim finding) {
        return "findings/" + (index + 1) + "-" + finding.id() + "/";
    }

    /** The fence is longer than any backtick run in the JSON, so a value holding a code block cannot close it early. */
    private static @Nullable String fenced(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        int longest = 0;
        for (var m = BACKTICKS.matcher(json); m.find(); )
            longest = Math.max(longest, m.group().length());
        String fence = "`".repeat(Math.max(3, longest + 1));
        return "# Classifier evidence\n\n" + fence + "json\n" + json.strip() + "\n" + fence + "\n";
    }

    private static final Pattern BACKTICKS = Pattern.compile("`+");

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
