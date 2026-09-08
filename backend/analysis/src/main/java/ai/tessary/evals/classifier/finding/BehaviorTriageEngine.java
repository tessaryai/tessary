// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import ai.tessary.evals.classifier.catalog.ClassifierMethodCard;
import ai.tessary.evals.classifier.finding.dossier.ClassifierDossierAssembler;
import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.evals.config.ClassifierProperties;
import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.open.obs.StructuredLog;
import ai.tessary.evals.prompt.PromptCraft;
import ai.tessary.evals.tenant.ApiKeyService;
import ai.tessary.evals.tenant.KeyScope;
import ai.tessary.evals.tenant.OrgMembership;
import ai.tessary.evals.tenant.OrgMembershipRepository;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Layer 2: audit ONE finding's CLAIM — is it true, sampled over enough, and carried by its evidence?
 *
 * <h2>Two files and a door</h2>
 *
 * <p>The dossier is {@code finding.md} (what the detector asserts, over what, since when, and how big
 * each evidence role is) and {@code state.json} (its own numbers, verbatim). That is all of it.
 * Everything else the agent needs — the traces, the spans, the sessions the claim is about — it fetches
 * for itself through this platform's MCP surface with a short-lived project key minted per run and
 * revoked in a {@code finally}.
 *
 * <p><b>This replaced a design that pre-chose the evidence.</b> The engine used to hydrate an exemplar
 * trace, and for a conformance baseline every violating conversation, into the dossier — so the sample
 * the agent ruled on was one this class picked, silently, before knowing the question. Now the sample is
 * the agent's: it pages {@code get_finding_evidence}, decides how much to read, and rule 5 makes it
 * state what it took. The population is uncapped at write time precisely so this decision can be made
 * at read time by whoever has to defend it.
 *
 * <p><b>And no repository.</b> The lane had an optional clone and a second prompt for the projects that
 * had one. A repository answers "why did this happen", which is RCA's question; it does not say whether
 * a claim about production traffic is true. Dropping it removed two prompts, a clone token round trip
 * and the lane vocabulary that went with them.
 *
 * <h2>What it may not do</h2>
 *
 * <p>The ruling is recorded on the finding and nothing else. It never re-pins a baseline, never writes
 * the allowlist, never resolves the finding. Absorbing a shift moves the reference a whole population is
 * compared against, and a machine doing that on one exemplar would blind the detector to that entire
 * class of drift. Resolution stays a human write ({@code classifiers/metric_drift/PROGRAM.md} §9).
 */
@Service
public class BehaviorTriageEngine {

    private static final Logger log = LoggerFactory.getLogger(BehaviorTriageEngine.class);

    /** The scope untagged traces collect under — no declared spec exists for it. */
    private static final String UNATTRIBUTED = BehaviorSubstrateRepository.UNATTRIBUTED;

    /**
     * How far a re-derived number may sit from the detector's own before the run is aborted.
     *
     * <p>Half a percent, relative: the payload rounds what it stores (four decimal places on the
     * statistics, quantiles in raw units), and an agent computing a rate over the same rows will land a
     * hair away from it. Anything past this is not rounding — it is two different populations, two
     * different windows, or a broken script, and this lane cannot tell which from here.
     */
    private static final double AGREEMENT_TOLERANCE = 0.005;

    /**
     * The dossier file carrying the detector's own numbers. Named {@code state.json} and not
     * {@code evidence.json}: it is a frozen copy of the detector's running STATE at the moment it fired
     * (for tool error, literally a row of {@code tool_error_state}), while "evidence" on this surface
     * means the ROWS behind the claim, which live in {@code finding_evidence} and are reached through
     * {@code get_finding_evidence}. One word for two unrelated things was costing the agent the
     * distinction the whole contract rests on.
     */
    public static final String STATE_FILE = "state.json";

    /**
     * What {@code summary} is for, said in the prompt as well as in the schema.
     *
     * <p>The schema's {@code maxLength} is a ceiling, not an instruction — a model that has not been
     * told the field is a headline writes to the limit and gets truncated, which is worse than a long
     * answer. So the shape is asked for here and bounded there. It matters beyond this screen: the same
     * string is the body of the Slack alert and of the case in Triage, and every one of them was
     * rendering a five-paragraph analysis where a sentence belonged.
     */
    /** Where this lane's prose lives: {@code analysis/src/main/resources/prompt-craft/triage/}. */
    private static final String TRIAGE = "triage";

    private static final String SUMMARY_RULE = PromptCraft.text(TRIAGE, "summary_rule.md");

    /**
     * The check that runs before the work, in every prompt this class builds.
     *
     * <p>Triage rules on evidence it fetches for itself, so the read surface is a precondition of the
     * task rather than a convenience within it. When it is unreachable the run has nothing to rule on —
     * and the vocabulary offered no way to say so: the closest available answer was {@code unclear},
     * which CLOSES the finding. One run took it. It closed a finding and folded 7,191 calls into a
     * detector's reference having read not one row, and its own summary said the surface was
     * unreachable. The information was there; the schema had nowhere to put it.
     *
     * <p>So the check is stated first, before the rules that assume it passed, and {@code blocked} is
     * offered wherever the verdicts are — a run that could not start fails and is retried, which is what
     * a missing prerequisite has always meant everywhere else in this queue.
     */
    private static final String PREFLIGHT = PromptCraft.text(TRIAGE, "preflight.md");

    /** Offered with the verdicts in every lane, because a prerequisite can fail in any of them. */
    public static final String BLOCKED_OPTION =
            "- `blocked` — a prerequisite failed and you could not read the evidence. Not a ruling: "
                    + "it fails this run for a later retry. Name what was unreachable.\n\n";

    /**
     * The eight rules, verbatim in every prompt this class builds.
     *
     * <p>They are the agreed specification for this layer, not prompt tuning: what triage is for (rule
     * 1), the one question it is competent to answer (2), the two ways a comparison lies (3, 4), how it
     * is obliged to work (5), and the two things it must never do — hedge into a queue that does not
     * exist (6), or reach for a cause it has no instrument to locate (7). Since B (#994), rule 8 covers
     * what to do if the run is forced to stop before finishing: a low-confidence verdict, never silence.
     * Change them here and every lane changes together, which is the point of there being one copy.
     */
    private static final String RULES = PromptCraft.text(TRIAGE, "rules.md");

    /**
     * How the agent reaches the substrate. Names the tools it may actually call — a tool named here that
     * the surface does not register costs a turn on {@code unknown tool}, and one the surface has that is
     * not named here is one it will not think to use.
     *
     * <p>Two facts are stated because both have burned a run: the wire is camelCase ({@code nextCursor}),
     * and a zero in {@code counts} AND {@code recordedCounts} under {@code baseline} is a real answer
     * rather than missing evidence.
     */
    private static final String MCP_DOOR = PromptCraft.text(TRIAGE, "mcp_door.md");

    /**
     * The workspace, and the contract that makes a computed answer trustworthy.
     *
     * <p>The abort is stated at length on purpose. It is the one rule whose consequence the agent cannot
     * observe (the run simply ends), and the failure it prevents — a plausible ruling resting on a script
     * that counted the wrong window — is the worst thing this lane can produce, because it is
     * indistinguishable from a good one downstream.
     */
    private static final String CHECKS_RULE = PromptCraft.text(TRIAGE, "checks_rule.md");

    /** What a citation is, on every lane. The downgrade is enforced in {@link BehaviorTriageVerdict}. */
    private static final String CITATION_RULE = PromptCraft.text(TRIAGE, "citation_rule.md");

    private final Map<String, TriageSandbox> sandboxes;
    private final ClassifierProperties props;
    private final ApiKeyService apiKeys;
    private final ProjectRepository projects;
    private final OrgMembershipRepository memberships;
    private final ObjectMapper mapper;
    private final FindingEvidenceRepository evidence;

    public BehaviorTriageEngine(
            List<TriageSandbox> sandboxList,
            ClassifierProperties props,
            ApiKeyService apiKeys,
            ProjectRepository projects,
            OrgMembershipRepository memberships,
            ObjectMapper mapper,
            FindingEvidenceRepository evidence) {
        Map<String, TriageSandbox> byKey = new HashMap<>();
        for (TriageSandbox s : sandboxList) byKey.put(s.key(), s);
        this.sandboxes = Map.copyOf(byKey);
        this.props = props;
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.memberships = memberships;
        this.mapper = mapper;
        this.evidence = evidence;
    }

    /**
     * Fail fast at startup when {@code evals.classifier.triage-sandbox} names no registered driver —
     * mirrors {@code AgenticDriftAnalyzer#validateSandboxConfig}. Triage has no alternate, non-agentic
     * path, so the check is unconditional rather than gated on "am I the active analyzer".
     *
     * <p>See {@code AgenticRcaEngine#validateSandboxConfig}'s javadoc (#857) for why an equivalent
     * unconditional blank-{@code evals.classifier.triage-mcp-base-url} check does NOT belong here: this
     * bean is instantiated in every app-context boot regardless of whether triage ever runs, no {@code
     * application.yml} sets the property (only {@code docker-compose}'s env injection does), and adding
     * the check breaks every {@code @SpringBootTest} in {@code app} that does not fake it — proven
     * concretely with {@code OpenApiSpecDriftTest}. The per-job throw in {@link #rule} stays the
     * enforcement point; the real new coverage for #857 is the localhost/blank guard in {@code
     * sandbox-runner/launcher/server.js}'s {@code runAgenticScript}, which runs once per real job.
     */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.getTriageSandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("evals.classifier.triage-sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    /**
     * One ruling run: mint the run's MCP key, sandbox, parse, check the arithmetic, revoke.
     *
     * <p><b>Throws when the run did not produce a ruling</b> — no sandbox, a launcher error, an answer
     * that does not parse, a check that contradicts the payload. That is the whole of the fail-open
     * reversal: the worker catches it, the job retries and eventually dead-letters, and
     * {@code triage_verdict} stays NULL. Recording {@code unclear} for a run that never happened is what
     * made a closed finding unre-openable, and {@code unclear} closes findings now.
     *
     * <p>The claim goes to the abort check straight from the source's brief, never re-read out of the
     * dossier map: a renamed file would otherwise disable the check without failing anything.
     *
     * <p>The key is issued to the project org's owner (deterministically, earliest membership first) —
     * triage is scheduled, never pressed by a person, so there is no triggering user to attribute it to,
     * and the audit trail names the finding it was minted for. It is revoked in a {@code finally}: the
     * key must not outlive the sandbox.
     */
    public BehaviorTriageVerdict rule(
            String projectId,
            String findingId,
            Map<String, String> dossier,
            String prompt,
            @Nullable String claimJson) {
        String mcpBase = props.getTriageMcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Not a degraded run — an impossible one. Without the MCP door the agent cannot open a single
            // row of the population it is auditing, so the only ruling it could reach is the claim read
            // back to us. Throwing dead-letters the job, which is a visible misconfiguration rather than
            // a silent stream of closures.
            throw new EvalsException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    "evals.classifier.triage-mcp-base-url is unset, so the agent has no way to read the evidence");
        }
        String principal = orgOwnerPrincipal(projectId);
        if (principal == null) {
            throw new EvalsException(
                    ClassifierError.TRIAGE_RUN_INCOMPLETE,
                    findingId,
                    "the project's org has no owner to issue a key to");
        }
        // ADMIN because that is the only family /mcp admits (KeyScope); the surface it reaches is
        // read-only, and the key lives for one run.
        ApiKeyService.Issued issued =
                apiKeys.issue(projectId, principal, "triage-" + findingId + " (system)", KeyScope.ADMIN);
        try {
            String sandboxKey = props.getTriageSandbox();
            TriageSandbox sandbox = sandboxes.get(sandboxKey);
            if (sandbox == null) {
                // The boot-time validator already guarantees this is registered — the same
                // belt-and-suspenders defensive guard AgenticDriftAnalyzer's callers use, not a path
                // expected to trip in practice. This is the launcher-selection fault, not a run
                // failure, so it takes the same error code an unreachable/misbehaving launcher does.
                throw new EvalsException(
                        ClassifierError.TRIAGE_LAUNCHER_UNAVAILABLE, "unknown triage sandbox '" + sandboxKey + "'");
            }
            TriageSandbox.SandboxRequest req = new TriageSandbox.SandboxRequest(
                    projectId,
                    findingId,
                    dossier,
                    prompt,
                    BehaviorTriageVerdict.JSON_SCHEMA,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext());

            Optional<TriageSandbox.SandboxRun> run = sandbox.run(req);
            if (run.isEmpty()) {
                throw new EvalsException(ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, "the sandbox did not run");
            }
            // Checked ahead of parse, which cannot tell a prerequisite failure from gibberish: both make
            // it return null, and only one of them has something to tell the person reading the job.
            String blocked =
                    BehaviorTriageVerdict.blockedReason(mapper, run.get().resultText());
            if (blocked != null) {
                throw new EvalsException(ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, blocked);
            }
            BehaviorTriageVerdict verdict =
                    BehaviorTriageVerdict.parse(mapper, run.get().resultText());
            if (verdict == null) {
                throw new EvalsException(
                        ClassifierError.TRIAGE_RUN_INCOMPLETE, findingId, "the agent returned no parseable ruling");
            }
            requireArithmeticAgrees(findingId, claimJson, verdict);
            return verdict;
        } finally {
            revokeQuietly(issued.token().id(), projectId, findingId);
        }
    }

    /**
     * Abort when a check script's re-derived number disagrees with the detector's own.
     *
     * <p>One of the two is wrong and nothing here can say which, so neither answer is recorded: the job
     * retries, dead-letters, and a person reads both numbers. Recording the ruling instead would ship a
     * confident audit built on arithmetic the platform had already seen fail — which is the failure this
     * lane can least afford, because downstream it looks exactly like a good ruling.
     *
     * <p>A pointer that does not resolve is not a contradiction and is ignored: the agent naming a field
     * the payload does not carry says nothing about the claim, and treating it as a mismatch would abort
     * runs over a typo.
     */
    private void requireArithmeticAgrees(
            String findingId, @Nullable String evidenceJson, BehaviorTriageVerdict verdict) {
        JsonNode payload = readTree(evidenceJson);
        if (payload == null) return;
        for (BehaviorTriageVerdict.Citation citation : verdict.citations()) {
            for (BehaviorTriageVerdict.Recomputed r : citation.recomputed()) {
                JsonNode stated = resolve(payload, r.pointer());
                if (stated == null || !stated.isNumber()) continue;
                if (agrees(stated.asDouble(), r.value())) continue;
                StructuredLog.error(log, Markers.OPS, "triage.check-contradicts-detector")
                        .field("finding", findingId)
                        .field("script", citation.path())
                        .field("pointer", r.pointer())
                        .field("detector", stated.asDouble())
                        .field("computed", r.value())
                        .log();
                throw new EvalsException(
                        ClassifierError.TRIAGE_RUN_INCOMPLETE,
                        findingId,
                        "check " + citation.path() + " computed " + r.pointer() + " = " + r.value()
                                + " against the detector's " + stated.asDouble()
                                + " — one of them is wrong, so no ruling is recorded");
            }
        }
    }

    private static boolean agrees(double stated, double computed) {
        double scale = Math.max(Math.abs(stated), Math.abs(computed));
        return Math.abs(stated - computed) <= AGREEMENT_TOLERANCE * scale + 1e-9;
    }

    /** Resolve a dotted pointer ({@code rate.cur}, {@code quantiles.p95[1]}); null when it leads nowhere. */
    private static @Nullable JsonNode resolve(JsonNode root, String pointer) {
        JsonNode node = root;
        for (String segment : pointer.replace("[", ".").replace("]", "").split("\\.")) {
            if (segment.isBlank()) continue;
            node = segment.chars().allMatch(Character::isDigit)
                    ? node.path(Integer.parseInt(segment))
                    : node.path(segment);
            if (node.isMissingNode()) return null;
        }
        return node;
    }

    private @Nullable JsonNode readTree(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** The org owner the run's key is issued to — earliest membership wins, so runs attribute stably. */
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

    /** Revocation must never mask the run's own outcome — log and move on; the audit row survives either way. */
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
     * The finding, as the two files the agent reads under {@code dossier/}.
     *
     * <p>Files rather than one long prompt, for the reason RCA already materializes its evidence this
     * way: an agent that can open, re-read and quote a file reasons over it better than one handed a
     * wall of JSON it must hold in context, and the numbers stay verbatim rather than being paraphrased
     * into prose on the way in.
     */
    public Map<String, String> dossier(BehaviorTriageJobRow job, FindingRow finding) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("finding.md", findingFile(job, finding));
        methodCard(finding.classifierKey()).ifPresent(card -> files.put("method.md", card));
        // D (#994): a dedicated per-shape assembler when this classifier's payload matches one (see
        // ClassifierDossierAssembler's dispatch-by-shape note); DossierPayload.forAgent's strip-only
        // pass otherwise. Either way the evidence-bias contract in DossierPayload's class javadoc holds.
        String assembled = ClassifierDossierAssembler.assemble(
                        mapper,
                        evidence,
                        finding.projectId(),
                        finding.id(),
                        evidenceCounts(finding),
                        finding.payloadJson())
                .orElseGet(() -> DossierPayload.forAgent(mapper, finding.payloadJson()));
        if (assembled != null && !assembled.isBlank()) {
            files.put(STATE_FILE, assembled);
        }
        return files;
    }

    /** {@link FindingRow#evidenceCount} for every role, read once for the dossier assembler. */
    private static ClassifierDossierAssembler.EvidenceCounts evidenceCounts(FindingRow finding) {
        return new ClassifierDossierAssembler.EvidenceCounts(
                finding.evidenceCount(FindingEvidenceRow.Role.EXEMPLAR),
                finding.evidenceCount(FindingEvidenceRow.Role.MEMBER),
                finding.evidenceCount(FindingEvidenceRow.Role.BASELINE),
                finding.evidenceCount(FindingEvidenceRow.Role.WITNESS),
                finding.evidenceCount(FindingEvidenceRow.Role.CHANGEPOINT));
    }

    /** What fired, over how much traffic, since when, and how big each side of the evidence is. */
    private String findingFile(BehaviorTriageJobRow job, FindingRow finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("# The finding\n\n")
                .append("- finding id: `")
                .append(finding.id())
                .append("` — the id every `get_finding_evidence` call takes\n")
                .append("- classifier: `")
                .append(finding.classifierKey())
                .append("`\n")
                .append(callSiteLine(finding))
                .append("- cause: ")
                .append(finding.causeKind())
                .append('\n')
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
        // No exemplar trace id. Handing the agent one trace as the way in decides for it which instance
        // the investigation is anchored on, which is the bias the evidence table stopped carrying when
        // the `exemplar` role was dropped. The counts below plus get_finding_evidence are the door.
        sb.append('\n').append(evidenceCountsSection(finding));
        sb.append('\n').append(causeExplanation(finding.causeKind()));
        return sb.toString();
    }

    /** The window the payload names, when it carries one — half of "do both sides measure the same thing". */
    private Optional<String> windowLine(FindingRow finding) {
        JsonNode window = readTreeOrMissing(finding.payloadJson()).path("window");
        if (!window.isObject()) return Optional.empty();
        return Optional.of("- window: " + window.path("opened_at").asText("?") + " → "
                + window.path("closed_at").asText("?")
                + (window.hasNonNull("kind") ? " (" + window.path("kind").asText() + ")" : "") + '\n');
    }

    /**
     * How many refs the detector wrote per role, from the finding's own counter.
     *
     * <p>Printed rather than left to the first MCP call because it is the one number that says what this
     * run COSTS to audit honestly: a member set of forty is read whole, one of two hundred thousand is
     * sampled and the sample stated. A zero is printed as a zero for the same reason the tool says so —
     * "no enumerable reference side" and "the write was interrupted" must not look alike.
     */
    private static String evidenceCountsSection(FindingRow finding) {
        StringBuilder sb = new StringBuilder("## The evidence the detector recorded\n\n");
        for (String role : FindingEvidenceRow.Role.ALL) {
            sb.append("- `")
                    .append(role)
                    .append("`: ")
                    .append(finding.evidenceCount(role))
                    .append(" ref(s)\n");
        }
        sb.append("\nThese are the counts written at finding-open. `get_finding_evidence(count_only=true)`"
                + " gives them again beside what still survives in the substrate; a live count BELOW these"
                + " is retention, not a lost write.\n");
        return sb.toString();
    }

    // ---- the prompts ---------------------------------------------------------------------------

    /** The classifier's method card, when one exists — absent for a user-authored classifier. */
    public static Optional<String> methodCard(@Nullable String classifierKey) {
        return Optional.ofNullable(ClassifierMethodCard.forClassifier(classifierKey));
    }

    /**
     * The behaviour/metric/tool-error ruling task: one claim, one question, and the outcomes phrased
     * against the cause that fired.
     */
    public String buildPrompt(BehaviorTriageJobRow job, FindingRow finding) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ruling on whether a classifier's CLAIM about production traffic is true.\n\n")
                .append("A detector flagged something statistically atypical. Statistically atypical is not the ")
                .append("same as real: a window can be too thin to mean anything, two windows can be measuring ")
                .append("different populations, and evidence can fail to show what the payload says it shows. ")
                .append("Your job is to audit the claim itself — not whether the behaviour it describes is good ")
                .append("or bad.\n\n");

        sb.append(whatYouHave(finding.id(), methodCard(finding.classifierKey()).isPresent()));

        sb.append(rulingOptions());

        sb.append(commonTail());
        return sb.toString();
    }

    /** The dossier layout, the MCP door and the workspace — identical on every lane, because they are. */
    public static String whatYouHave(String findingId, boolean hasMethodCard) {
        StringBuilder sb = new StringBuilder("## What you have\n\n");
        sb.append("What you have is the dossier below, and not one row of traffic. **Nothing you were given names an")
                .append(" individual trace or span.** That is deliberate: an example we chose would decide")
                .append(" which instance you anchored on, and you could not tell our pick from a draw you")
                .append(" made yourself — so neither of us could correct for it. You take the sample, and")
                .append(" you say which one you took.\n\n");
        sb.append("`dossier/finding.md` — what the detector asserts, over what, since when, and how many")
                .append(" evidence refs it recorded per role.\n");
        if (hasMethodCard) {
            sb.append("`dossier/method.md` — how THIS detector works: what it measures, what it compared")
                    .append(" against, and what an empty role legitimately means for it. Read it before you")
                    .append(" read a zero as a missing write.\n");
        }
        sb.append("`dossier/")
                .append(STATE_FILE)
                .append("` — the detector's own numbers at the moment it fired, verbatim. Every dotted")
                .append(" pointer you cite (`window.n_cur`, `rate.cur`) resolves in this file.\n\n");
        sb.append("`")
                .append(STATE_FILE)
                .append("` is the CLAIM. The EVIDENCE is the rows behind it, and those are not in the")
                .append(" dossier at all — they are behind MCP, enumerated, and yours to page. Two")
                .append(" different things; do not cite one for the other.\n\n");
        sb.append("The finding id is `").append(findingId).append("`.\n\n");
        return sb.append(MCP_DOOR).append('\n').append(CHECKS_RULE).append('\n').toString();
    }

    /** Which call site raised this, so the agent knows whether a declared spec exists for it at all. */
    private static String callSiteLine(FindingRow finding) {
        String callSite = finding.callSiteId();
        if (callSite == null || callSite.isBlank() || UNATTRIBUTED.equals(callSite)) {
            return "- call site: none — the producer tagged no call site on these traces, so there is no "
                    + "declared spec for them.\n";
        }
        return "- call site: `" + callSite + "`\n";
    }

    /**
     * The three verdicts, defined by what each one MEANS.
     *
     * <p><b>No enumerated failure modes, deliberately.</b> These bullets used to list ways a claim can
     * fail — "spread evenly across signatures that were always there", "the sample is too thin". Two of
     * those named a before-side signature comparison the payload structurally cannot carry, and every
     * one of them narrows the agent to the list: an example in a verdict definition becomes the thing it
     * pattern-matches for. The run that found three simultaneous calls behind one "rise" was told to
     * look for no such thing. What makes a ruling honest is rule 5, which forces it to compute, and
     * {@code method.md}, which says how the detector works.
     */
    static String rulingOptions() {
        return "## How to rule\n\n"
                + "- `positive` — the claim holds: what the detector asserts really happened, and the "
                + "evidence you examined carries it.\n"
                + "- `negative` — the claim does not hold: the evidence, read for yourself, does not "
                + "carry what the detector asserts.\n"
                + "- `unclear` — the evidence does not settle it.\n\n"
                + BLOCKED_OPTION;
    }

    /**
     * What every lane's prompt ends with, in the order it ends with it: the prerequisite check, the
     * eight rules, the citation contract, the summary contract.
     *
     * <p>One method rather than the same four appends written out three times — which is what it was,
     * and is why the preflight had to be added in three places and could have been added in two. A test
     * holds this, and through it holds all three lanes.
     */
    public static String commonTail() {
        return PREFLIGHT + RULES + '\n' + CITATION_RULE + '\n' + SUMMARY_RULE;
    }

    /** Test seam: one cause's framing, without assembling a prompt around it. */
    static String causeText(String causeKind) {
        return causeExplanation(causeKind);
    }

    /** What the cause actually asserts, so the agent rules on the right claim. */
    private static String causeExplanation(String causeKind) {
        return switch (causeKind) {
            case FindingRow.Cause.OMISSION ->
                "This is an OMISSION: the listed step(s) appear in almost every other trace this agent "
                        + "produces, and this trace performed none of them. Ask whether that step is required "
                        + "for this kind of request, or whether it is legitimately conditional.\n";
            case FindingRow.Cause.NOVELTY ->
                "This is a NOVELTY: the listed action sequence is one the agent has not performed before. "
                        + "New is not the same as wrong — ask whether this action is authorized here at all.\n";
            case FindingRow.Cause.SURPRISAL ->
                "This is a SURPRISAL: the listed transition is one the agent makes far more rarely than its "
                        + "alternatives at that point. Rare is not wrong — ask whether this is an acceptable "
                        + "path.\n";
            case FindingRow.Cause.RATE_SHIFT ->
                "This is a RATE SHIFT: one tool's failure rate has moved against its pinned in-control "
                        + "rate, in `state.json` under `rate.ref`, against `rate.cur` now. No individual call "
                        + "is being called wrong, and the tool failing sometimes is normal — the question is "
                        + "whether these failures are a problem. The failing calls are evidence role "
                        + "`witness` and the whole population is `member`; `method.md` states how the "
                        + "detector reached this claim, and page the refs with get_finding_evidence.\n";
            case FindingRow.Cause.DISTRIBUTION_SHIFT ->
                "This is a DISTRIBUTION SHIFT: a whole population of turns (or tool calls) now sits "
                        + "measurably away from where that same population sat before — slower, faster, more expensive "
                        + "or cheaper. No individual trace is being called wrong, and a member of it is a MEMBER "
                        + "of the shifted population rather than an anomaly in it: a forty-second research run "
                        + "and a $0.40 turn are both routinely correct, which is why the bar is the reference "
                        + "the cause key names — `:pinned` or `:previous`, both defined in `method.md` — "
                        + "rather than any absolute limit. The question is whether "
                        + "the AGENT changed — more retries, a bigger prompt, an extra hop, a model swap, a "
                        + "prompt edit that stopped the cache hitting — or whether the TRAFFIC changed. The "
                        + "workload block in `state.json` is what separates those two: it reports what users "
                        + "asked for, then and now, beside the measure that moved. Flat inputs with moved "
                        + "outputs means the agent changed. Inputs that moved with the measure means the "
                        + "traffic did. Both readings are checkable against the `member` refs — and "
                        + "`baseline` on a `:pinned` finding. Check them.\n";
            default -> "";
        };
    }

    // A "## When this started" block used to sit in the prompt here, naming the commit the agent was
    // running when the behaviour appeared. It is gone, and not for brevity: the commit came from the
    // deploy of ONE sampled trace, stated as the finding's deploy — so a window spanning two releases
    // named one of them and the agent had no way to know. It also dangled a cause at an agent that rule
    // 7 forbids from locating causes, using an instrument (the repository) this lane does not have.
    // A deploy set derived from the whole flagged window would be a population fact and could return
    // here; one trace's version cannot.

    private JsonNode readTreeOrMissing(@Nullable String json) {
        JsonNode node = readTree(json);
        return node == null ? mapper.missingNode() : node;
    }

    /**
     * Serialize citations for {@code finding.triage_citations}.
     *
     * <p>One shape whatever the citation is: a dotted pointer into the evidence, an id the agent fetched,
     * or a check script with the stdout it printed and the numbers it re-derived. Separate columns would
     * have bought a type distinction and cost every reader a branch.
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
