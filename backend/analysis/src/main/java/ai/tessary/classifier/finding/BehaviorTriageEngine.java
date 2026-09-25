// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierMethodCard;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.prompt.PromptCraft;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Layer 2: audits one finding's claim, checking whether it is true, sampled over enough, and carried
 * by its evidence.
 *
 * <p>The dossier the agent gets is {@code finding.md} (the finding's facts: the claim, where and since
 * when it was seen, and how many evidence refs the detector recorded per role) and {@code method.md}
 * (how this detector works and what its evidence means, including what a zero legitimately signifies).
 * The claim's own numbers are not shipped as a file: the agent reads them off {@code get_finding}, the
 * same door it pages evidence through, so the dossier never drifts out of step with what the surface
 * actually reports. Everything the agent needs — the traces, spans and sessions the claim is about — it
 * fetches itself through this platform's MCP surface with a short-lived project key minted per run and
 * revoked in a {@code finally}. The agent chooses its own sample rather than being handed a pre-picked
 * exemplar: it pages {@code get_finding_evidence}, decides how much to read, and states what it took.
 *
 * <p>The ruling is recorded on the finding and nothing else. It never re-pins a baseline, never writes
 * the allowlist, never resolves the finding: absorbing a shift moves the reference a whole population
 * is compared against, and a machine doing that on one exemplar would blind the detector to that
 * entire class of drift. Resolution stays a human write.
 */
@Service
public class BehaviorTriageEngine {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageEngine.class);

    /** The scope untagged traces collect under: no declared spec exists for it. */
    private static final String UNATTRIBUTED = BehaviorSubstrateRepository.UNATTRIBUTED;

    /** Where this lane's prose lives: {@code analysis/src/main/resources/prompt-craft/triage/}. */
    private static final String TRIAGE = "triage";

    /**
     * The whole system prompt, one copy for every classifier: the goals and invariants of the job, and
     * nothing that changes per run — a finding's own facts live in {@code finding.md}/{@code method.md}
     * and the user message instead, so this string is identical across every triage run and every model
     * this lane offers, which is what makes it worth caching on the provider side.
     */
    static final String SYSTEM_PROMPT = PromptCraft.text(TRIAGE, "system_prompt.md");

    /**
     * opencode reserves a couple of turns of its own budget to force a text-only final answer once the
     * agent's cap is hit (see {@code Agentic#maxTurns}'s javadoc for the mechanism); the number stated to
     * the agent is the turns it actually gets to work with, not the raw config value.
     */
    private static final int TURN_RESERVE = 2;

    private final Map<String, TriageSandbox> sandboxes;
    private final ClassifierProperties props;
    private final ObserverProperties observerProps;
    private final ApiKeyService apiKeys;
    private final ProjectRepository projects;
    private final OrgMembershipRepository memberships;
    private final ObjectMapper mapper;
    private final ClassifierDetectionWriteRepository detections;

    public BehaviorTriageEngine(
            List<TriageSandbox> sandboxList,
            ClassifierProperties props,
            ObserverProperties observerProps,
            ApiKeyService apiKeys,
            ProjectRepository projects,
            OrgMembershipRepository memberships,
            ObjectMapper mapper,
            ClassifierDetectionWriteRepository detections) {
        Map<String, TriageSandbox> byKey = new HashMap<>();
        for (TriageSandbox s : sandboxList) byKey.put(s.key(), s);
        this.sandboxes = Map.copyOf(byKey);
        this.props = props;
        this.observerProps = observerProps;
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.memberships = memberships;
        this.mapper = mapper;
        this.detections = detections;
    }

    /**
     * Fail fast at startup when {@code tessary.classifier.triage-sandbox} names no registered driver.
     * Triage has no alternate, non-agentic path, so the check is unconditional rather than gated on
     * whether triage is the active analyzer.
     *
     * <p>No equivalent check for a blank {@code tessary.classifier.triage-mcp-base-url} belongs here:
     * this bean is instantiated on every app-context boot regardless of whether triage ever runs, and
     * no {@code application.yml} sets that property. The per-job throw in {@link #rule} is the
     * enforcement point for it instead.
     */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.getTriageSandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("tessary.classifier.triage-sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    /**
     * One ruling run: mint the run's MCP key, sandbox, parse, revoke.
     *
     * <p>Throws when the run did not produce a ruling: no sandbox, a launcher error, an answer that
     * does not parse or carries no citation. The worker catches it, the job retries and eventually
     * dead-letters, and {@code triage_verdict} stays NULL rather than being recorded for a run that
     * never actually established anything.
     *
     * <p>The key is issued to the project org's owner (deterministically, earliest membership first),
     * since triage is scheduled rather than pressed by a person, and is revoked in a {@code finally}:
     * the key must not outlive the sandbox.
     */
    public BehaviorTriageVerdict rule(String projectId, String findingId, Map<String, String> dossier, String prompt) {
        String mcpBase = props.getTriageMcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Without the MCP door the agent cannot open a single row of the population it is auditing,
            // so throwing dead-letters the job as a visible misconfiguration rather than a silent
            // stream of closures.
            throw new TessaryException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    "tessary.classifier.triage-mcp-base-url is unset, so the agent has no way to read the evidence");
        }
        String principal = orgOwnerPrincipal(projectId);
        if (principal == null) {
            throw new TessaryException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    "the project's org has no owner to issue a key to");
        }
        // ADMIN because that is the only family /mcp admits; the surface it reaches is read-only, and
        // the key lives for one run.
        ApiKeyService.Issued issued =
                apiKeys.issue(projectId, principal, "triage-" + findingId + " (system)", KeyScope.ADMIN);
        try {
            // validateSandboxConfig already refused to boot without this key registered.
            TriageSandbox sandbox = Objects.requireNonNull(sandboxes.get(props.getTriageSandbox()));
            TriageSandbox.SandboxRequest req = new TriageSandbox.SandboxRequest(
                    projectId,
                    findingId,
                    dossier,
                    prompt,
                    BehaviorTriageVerdict.JSON_SCHEMA,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext(),
                    SYSTEM_PROMPT);

            Optional<TriageSandbox.SandboxRun> run = sandbox.run(req);
            if (run.isEmpty()) {
                throw new TessaryException(ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, "the sandbox did not run");
            }
            // Checked ahead of parse, which cannot tell a prerequisite failure from gibberish: both
            // return null, and only one of them has something to tell the person reading the job.
            String blocked =
                    BehaviorTriageVerdict.blockedReason(mapper, run.get().resultText());
            if (blocked != null) {
                throw new TessaryException(ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, blocked);
            }
            BehaviorTriageVerdict verdict =
                    BehaviorTriageVerdict.parse(mapper, run.get().resultText());
            if (verdict == null) {
                throw new TessaryException(
                        ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, "the agent returned no parseable ruling");
            }
            return verdict;
        } finally {
            revokeQuietly(issued.token().id(), projectId, findingId);
        }
    }

    /** The org owner the run's key is issued to: earliest membership wins, so runs attribute stably. */
    private @Nullable String orgOwnerPrincipal(String projectId) {
        return projects.findById(projectId)
                .flatMap(p -> memberships.findByOrg(p.orgId()).stream()
                        .filter(OrgMembership::isOwner)
                        .sorted(Comparator.comparing(OrgMembership::createdAt)
                                .thenComparing(OrgMembership::principalId))
                        .findFirst())
                .map(OrgMembership::principalId)
                .orElse(null);
    }

    /** Revocation must never mask the run's own outcome: log and move on. The audit row survives either way. */
    private void revokeQuietly(String tokenId, String projectId, String findingId) {
        try {
            apiKeys.revoke(tokenId);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "triage.key-revoke-failed")
                    .field("project", projectId)
                    .field("finding", findingId)
                    .field("token", tokenId)
                    .field("error", e.getMessage())
                    .log();
        }
    }

    // ---- the dossier ---------------------------------------------------------------------------

    /**
     * The finding, as the two files the agent reads under {@code dossier/}: {@code finding.md} (this
     * finding's own facts) and {@code method.md} (how the detector that filed it works), when one
     * exists. Files rather than one long prompt: an agent that can open, re-read and quote a file
     * reasons over it better than one handed a wall of JSON it must hold in context.
     *
     * <p>No third file carrying the detector's numbers verbatim. The claim's numbers live in
     * {@code get_finding}, one call away over the same MCP door the agent already pages evidence
     * through, so there is exactly one place they can be read from and it cannot drift out of step
     * with what {@code method.md} describes.
     */
    public Map<String, String> dossier(BehaviorTriageJobRow job, FindingRow finding) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("finding.md", findingFile(finding));
        methodCard(finding.classifierKey()).ifPresent(card -> files.put("method.md", card));
        detectionsFile(finding).ifPresent(text -> files.put(DETECTIONS_FILE, text));
        return files;
    }

    /** The dossier file listing a groundedness rate finding's flagged answers, one per flagged span. */
    static final String DETECTIONS_FILE = "detections.md";

    /** Rows listed before the file says there are more: enough to read the pattern, bounded for the budget. */
    static final int DETECTIONS_CAP = 50;

    /**
     * For a groundedness rate finding, what the classifier wrote about each answer it flagged at the
     * finding's call site since onset: the score and the sentences it marked. The evidence enumeration says
     * WHICH answers; this says WHERE in each the model saw an unsupported sentence, which is what a ruling
     * on a groundedness finding is made of, and handing it over saves the agent a page of MCP reads per
     * answer. Empty for every other cause kind.
     */
    private Optional<String> detectionsFile(FindingRow finding) {
        if (!FindingRow.Cause.GROUNDEDNESS_RATE.equals(finding.causeKind())) return Optional.empty();
        String callSite = finding.callSiteId() != null ? finding.callSiteId() : finding.nativeCauseKey();
        List<ClassifierDetectionWriteRepository.DetectionInWindow> rows = detections.listWitnessDetections(
                BuiltInDetector.Kind.GROUNDEDNESS,
                finding.projectId(),
                finding.subjectId(),
                callSite,
                finding.onsetAt(),
                endOfLastHour(finding.lastSeenAt()),
                DETECTIONS_CAP + 1);
        StringBuilder sb = new StringBuilder("# Flagged answers since onset\n\n")
                .append("One line per answer the classifier flagged at this call site, newest first: trace and")
                .append(" span ids (the `get_trace` / `get_span` arguments), when the span ran, the answer's")
                .append(" score (P(unsupported) of its strongest sentence), and each flagged sentence as")
                .append(" `[start, end)` offsets into the answer (UTF-16 code units) with its own score. The")
                .append(" strongest sentence's text follows in quotes. A flagged sentence is where the model")
                .append(" saw no support in the retrieved documents, not proof that the sentence is wrong.\n\n");
        int shown = Math.min(rows.size(), DETECTIONS_CAP);
        for (int i = 0; i < shown; i++) {
            ClassifierDetectionWriteRepository.DetectionInWindow d = rows.get(i);
            sb.append("- trace `")
                    .append(d.traceId())
                    .append("` span `")
                    .append(d.spanId())
                    .append("` at ")
                    .append(d.subjectStartedAt())
                    .append(": ")
                    .append(flaggedAnswerLine(d.evidenceJson()))
                    .append('\n');
        }
        if (rows.size() > DETECTIONS_CAP) {
            sb.append("\nThe newest ")
                    .append(DETECTIONS_CAP)
                    .append(" shown; page the rest through `get_finding_evidence`.\n");
        } else {
            sb.append("\n").append(shown).append(" flagged answer(s), every one since onset.\n");
        }
        return Optional.of(sb.toString());
    }

    /** {@code score 0.991; flagged [0, 42) 0.991, [80, 131) 0.978; strongest: "..."}, from a detection's evidence. */
    private String flaggedAnswerLine(@Nullable String evidenceJson) {
        if (evidenceJson == null) return "(no evidence recorded)";
        JsonNode ev;
        try {
            ev = mapper.readTree(evidenceJson);
        } catch (Exception e) {
            return evidenceJson;
        }
        StringBuilder line =
                new StringBuilder("score ").append(ev.path("unsupported").asText("?"));
        JsonNode sentences = ev.path("flagged_sentences");
        if (sentences.isArray() && !sentences.isEmpty()) {
            line.append("; flagged ");
            for (int i = 0; i < sentences.size(); i++) {
                JsonNode s = sentences.get(i);
                if (i > 0) line.append(", ");
                line.append('[')
                        .append(s.path("start").asInt())
                        .append(", ")
                        .append(s.path("end").asInt())
                        .append(") ")
                        .append(s.path("unsupported").asText("?"));
            }
        }
        String claim = ev.path("claim").asText("");
        if (!claim.isBlank())
            line.append("; strongest: \"").append(claim.replace('\n', ' ')).append('"');
        return line.toString();
    }

    /** The end of the hour a finding was last seen in, where its counts stop; now when it cannot be read. */
    private static String endOfLastHour(String lastSeenAt) {
        try {
            return Instant.parse(lastSeenAt).plus(Duration.ofHours(1)).toString();
        } catch (DateTimeParseException e) {
            return Instant.now().toString();
        }
    }

    /**
     * Facts about this finding only: what fired, over what, since when, and how big each side of the
     * evidence is. No detector method (that lives in {@code method.md}) and no cause explanation
     * (that is the cause section of {@code method.md}) and no detector numbers beyond the one-sentence
     * claim — those are frozen on {@code get_finding} once the finding is triaged.
     */
    private String findingFile(FindingRow finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("# The finding\n\n")
                .append("- finding id: `")
                .append(finding.id())
                .append("`\n");
        claimLine(finding).ifPresent(sb::append);
        sb.append("- classifier: `")
                .append(finding.classifierKey())
                .append("`\n")
                .append(callSiteLine(finding))
                .append("- cause: `")
                .append(finding.causeKind())
                .append('`')
                .append(
                        methodCard(finding.classifierKey()).isPresent()
                                ? ". Read the matching section of `dossier/method.md`.\n"
                                : "\n")
                .append("- pattern: `")
                .append(finding.causeKey())
                .append("`\n")
                .append("- observed over ")
                .append(finding.sampleCount())
                .append(" sample(s), first at ")
                .append(finding.onsetAt())
                .append(", most recently at ")
                .append(finding.lastSeenAt())
                .append('\n');
        windowLine(finding).ifPresent(sb::append);
        sb.append('\n').append(evidenceCountsSection(finding));
        return sb.toString();
    }

    /**
     * The finding's title from {@link FindingTitle}, dropped when {@link FindingTitle} could not read the
     * payload: the `pattern`
     * line already shows the cause key in that case, and repeating it as a fake "claim" would be noise.
     */
    private static Optional<String> claimLine(FindingRow finding) {
        String title = FindingTitle.of(finding);
        if (title.equals(finding.nativeCauseKey())) return Optional.empty();
        return Optional.of("- claim: " + title + "\n");
    }

    /**
     * The window the payload names, when it carries one: half of "do both sides measure the same thing".
     *
     * <p>The payload's {@code kind} is deliberately not printed. It distinguishes a window that closed
     * once from a spell whose numbers are recomputed from an hourly aggregate on every read, which is a
     * fact about the detector rather than about this finding, and each method card states it in its own
     * words already. Bare, the value is jargon at the point it is read.
     */
    private static Optional<String> windowLine(FindingRow finding) {
        JsonNode window = finding.payload().path("window");
        if (!window.isObject()) return Optional.empty();
        return Optional.of("- window: " + window.path("opened_at").asText("?") + " to "
                + window.path("closed_at").asText("?") + '\n');
    }

    /** Which call site raised this, in the three shapes a finding's call site can take. */
    private static String callSiteLine(FindingRow finding) {
        String callSite = finding.callSiteId();
        if (callSite == null || callSite.isBlank() || UNATTRIBUTED.equals(callSite)) {
            return "- call site: none. The producer tagged no call site on these traces, so no declared "
                    + "spec exists for them.\n";
        }
        if ("tool".equals(finding.payload().path("bucket").path("kind").asText(""))) {
            return "- call site: `" + callSite + "`, the largest of the call sites this tool bucket "
                    + "spans. Each evidence row carries its own `callSiteId`.\n";
        }
        return "- call site: `" + callSite + "`\n";
    }

    /**
     * How many refs the detector wrote per role, from the finding's own counter. Printed rather than
     * left to the first MCP call because it says what this run costs to audit honestly: a member set
     * of forty is read whole, one of two hundred thousand is sampled and the sample stated.
     */
    private static String evidenceCountsSection(FindingRow finding) {
        StringBuilder sb = new StringBuilder("## Evidence the detector recorded\n\n");
        for (String role : FindingEvidenceRow.Role.ALL) {
            sb.append("- `")
                    .append(role)
                    .append("`: ")
                    .append(finding.evidenceCount(role))
                    .append(" row(s)\n");
        }
        sb.append("\nThese are the counts written when the finding opened.\n");
        return sb.toString();
    }

    // ---- the user message ------------------------------------------------------------------------

    /** The classifier's method card, when one exists: absent for a user-authored classifier. */
    public static Optional<String> methodCard(@Nullable String classifierKey) {
        return Optional.ofNullable(ClassifierMethodCard.forClassifier(classifierKey));
    }

    /**
     * The per-run task: the finding id, the dossier file list, and the budget this run gets — the only
     * things that change per finding or per deployment. Everything else the agent needs to know how to
     * work is in {@link #SYSTEM_PROMPT}, which carries nothing per-run.
     */
    public String buildPrompt(BehaviorTriageJobRow job, FindingRow finding) {
        boolean hasMethodCard = methodCard(finding.classifierKey()).isPresent();
        ObserverProperties.Agentic agentic = observerProps.getAgentic();
        int maxTurns = Math.max(0, agentic.getMaxTurns() - TURN_RESERVE);

        StringBuilder sb = new StringBuilder();
        sb.append("Rule on finding `").append(finding.id()).append("`.\n\n");
        sb.append("The dossier is under `./dossier/`:\n");
        sb.append("- `dossier/finding.md`\n");
        if (hasMethodCard) {
            sb.append("- `dossier/method.md`\n");
        }
        sb.append('\n');
        if (!hasMethodCard) {
            sb.append("This detector has no method card; its rule is whatever its author configured.\n\n");
        }
        sb.append("You have ")
                .append(maxTurns)
                .append(" turns. Every tool call is a turn. Record your ruling before they run out.\n");
        return sb.toString();
    }

    /**
     * Serialize citations for {@code finding.triage_citations}. One shape whatever the citation is: a
     * dotted pointer into the evidence, an id the agent fetched, or a check script with the stdout it
     * printed.
     */
    public @Nullable String citationsJson(BehaviorTriageVerdict verdict) {
        if (verdict.citations().isEmpty()) return null;
        try {
            return mapper.writeValueAsString(verdict.citations());
        } catch (Exception e) {
            return null;
        }
    }
}
