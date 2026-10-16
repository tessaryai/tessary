// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.ClassifierDetectionWriteRepository;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierMethodCard;
import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.config.ClassifierProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
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
 * The agent fetches traces itself over MCP with a short-lived project key revoked in a {@code finally}, and chooses its
 * own sample. The ruling is recorded on the finding and nothing else: it never re-pins a baseline, writes the allowlist
 * or resolves, because a machine moving the reference on one exemplar would blind the detector to that class of drift.
 */
@Service
public class BehaviorTriageEngine {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageEngine.class);

    private static final String UNATTRIBUTED = BehaviorSubstrateRepository.UNATTRIBUTED;

    private static final String TRIAGE = "triage";

    /**
     * Nothing per-run lives here, so the string is identical across runs and models, which makes it cacheable on the
     * provider side.
     */
    static final String SYSTEM_PROMPT = PromptCraft.text(TRIAGE, "system_prompt.md");

    /**
     * opencode reserves a couple of turns to force a text-only final answer at the cap, so the number stated to the
     * agent is the turns it actually gets.
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
    private final FindingAdapters adapters;

    public BehaviorTriageEngine(
            List<TriageSandbox> sandboxList,
            ClassifierProperties props,
            ObserverProperties observerProps,
            ApiKeyService apiKeys,
            ProjectRepository projects,
            OrgMembershipRepository memberships,
            ObjectMapper mapper,
            ClassifierDetectionWriteRepository detections,
            FindingAdapters adapters) {
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
        this.adapters = adapters;
    }

    /**
     * Unconditional because triage has no non-agentic path. A blank triage-mcp-base-url is not checked here, since this
     * bean boots everywhere and no {@code application.yml} sets it; {@link #rule} enforces it per job.
     */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.triageSandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("tessary.classifier.triage-sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    /**
     * Throws when the run did not produce a cited ruling, so the job dead-letters and {@code triage_verdict} stays
     * NULL. The key goes to the org owner, since triage is scheduled rather than pressed by a person.
     */
    public BehaviorTriageVerdict rule(String projectId, String findingId, Map<String, String> dossier, String prompt) {
        String mcpBase = props.triageMcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Without the MCP door the agent cannot open a single row, so dead-letter as a visible misconfiguration
            // rather than a silent stream of closures.
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
        // ADMIN because that is the only family /mcp admits; the surface it reaches is read-only, and the key lives for
        // one run.
        ApiKeyService.Issued issued =
                apiKeys.issue(projectId, principal, "triage-" + findingId + " (system)", KeyScope.ADMIN);
        try {
            // validateSandboxConfig refuses to boot without this key registered.
            TriageSandbox sandbox = Objects.requireNonNull(sandboxes.get(props.triageSandbox()));
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
            // Checked ahead of parse, which returns null for both a prerequisite failure and gibberish.
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

    /** Earliest membership wins, so runs attribute stably. */
    private @Nullable String orgOwnerPrincipal(String projectId) {
        return projects.findById(projectId)
                .flatMap(p -> OrgMembership.eldestOwner(memberships.findByOrg(p.orgId())))
                .orElse(null);
    }

    /** Revocation must never mask the run's own outcome. */
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

    /**
     * The detector's numbers are not shipped as a file: they live in {@code get_finding}, so there is one place to read
     * them and it cannot drift.
     */
    public Map<String, String> dossier(BehaviorTriageJobRow job, FindingRow finding) {
        Map<String, String> files = new LinkedHashMap<>();
        Optional<String> card = methodCard(finding);
        files.put("finding.md", findingFile(finding, card.isPresent()));
        card.ifPresent(text -> files.put("method.md", text));
        detectionsFile(finding).ifPresent(text -> files.put(DETECTIONS_FILE, text));
        return files;
    }

    static final String DETECTIONS_FILE = "detections.md";

    static final int DETECTIONS_CAP = 50;

    /**
     * Saves the agent a page of MCP reads per answer by saying where in each answer the unsupported sentences were.
     * Empty for every other cause kind.
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

    private static String endOfLastHour(String lastSeenAt) {
        try {
            return Instant.parse(lastSeenAt).plus(Duration.ofHours(1)).toString();
        } catch (DateTimeParseException e) {
            return Instant.now().toString();
        }
    }

    /** No detector method or numbers beyond the claim: those live in {@code method.md} and {@code get_finding}. */
    private String findingFile(FindingRow finding, boolean hasMethod) {
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
                .append(hasMethod ? ". Read the matching section of `dossier/method.md`.\n" : "\n")
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

    /** Dropped when the payload could not be read: the `pattern` line already shows the cause key. */
    private static Optional<String> claimLine(FindingRow finding) {
        String title = FindingTitle.of(finding);
        if (title.equals(finding.nativeCauseKey())) return Optional.empty();
        return Optional.of("- claim: " + title + "\n");
    }

    /**
     * The payload's {@code kind} is deliberately not printed: it is a detector fact each method card already states,
     * and bare it is jargon.
     */
    private static Optional<String> windowLine(FindingRow finding) {
        JsonNode window = finding.payload().path("window");
        if (!window.isObject()) return Optional.empty();
        return Optional.of("- window: " + window.path("opened_at").asText("?") + " to "
                + window.path("closed_at").asText("?") + '\n');
    }

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
     * Printed up front because it says what an honest audit costs: forty refs are read whole, two hundred thousand are
     * sampled.
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

    public Optional<String> methodCard(FindingRow finding) {
        String card = ClassifierMethodCard.forClassifier(finding.classifierKey());
        return card != null ? Optional.of(card) : adapters.triageMethod(finding.claim());
    }

    /** Only per-run facts: everything else is in {@link #SYSTEM_PROMPT} so that stays cacheable. */
    public String buildPrompt(BehaviorTriageJobRow job, FindingRow finding) {
        boolean hasMethodCard = methodCard(finding).isPresent();
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

    /** Null when there are none, so the column reads as uncited rather than as an empty list. */
    public @Nullable String citationsJson(BehaviorTriageVerdict verdict) {
        if (verdict.citations().isEmpty()) return null;
        return writeCitations(verdict.citations());
    }

    @ExcludeFromJacocoGeneratedReport("defensive: the mapper is the app's own and writes these string-only records")
    private @Nullable String writeCitations(List<BehaviorTriageVerdict.Citation> citations) {
        try {
            return mapper.writeValueAsString(citations);
        } catch (Exception e) {
            return null;
        }
    }
}
