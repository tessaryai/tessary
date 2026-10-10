// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.config.RcaProperties;
import ai.tessary.config.RcaProperties.Agentic;
import ai.tessary.git.GitCloneUrls;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.prompt.PromptCraft;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every RCA's analysis stage: hand the investigation to an agent session in a fresh E2B microVM (via
 * {@link E2bRcaSandbox}) with three sources of evidence:
 *
 * <ol>
 *   <li>the finding's dossier as files — the claim, the detector's own numbers, the classifier's RCA method
 *       when it has one, and the MCP tools (assembled by {@link RcaAnalysisService});</li>
 *   <li>a live MCP connection back to this platform, authed with a short-lived key minted for the run
 *       and revoked in a {@code finally}. <b>Required</b>: the dossier states the claim and the
 *       substrate behind it is read on demand, so a run without the door cannot open a single row of
 *       the evidence it is investigating;</li>
 *   <li>the project repo, cloned at the integration's default-branch HEAD, <b>when there is one</b>.
 *       An evidence-only project still gets an investigation; it says the code side is unread.</li>
 * </ol>
 *
 * <p>One prompt ({@code prompt-craft/rca/prompt.md}) and one output schema serve every classifier. Which
 * snippets apply to a run is decided here; the prose lives in {@code prompt-craft/rca/}. The output still
 * goes through {@link RcaSynthesisOutput} receipt-validation, so no cause can cite an id outside this
 * finding's evidence.
 *
 * <p><b>Nothing in the prompt discloses that a triage pass ran on this finding</b> — see the firewall
 * section of {@link RcaAnalysisService}. Not the ruling, not its summary, not its existence.
 */
@Service
public class AgenticRcaEngine {

    private static final Logger log = LoggerFactory.getLogger(AgenticRcaEngine.class);

    /** Where this lane's prose lives: {@code prompt-craft/rca/}. */
    private static final String RCA = "rca";

    /** The agent's final-output contract, one for every classifier. */
    static final String JSON_SCHEMA = PromptCraft.text(RCA, "response_schema.json");

    private static final String PROMPT = PromptCraft.text(RCA, "prompt.md");
    private static final String REPO_PRESENT = PromptCraft.text(RCA, "repo_present.md");
    private static final String REPO_ABSENT = PromptCraft.text(RCA, "repo_absent.md");
    private static final String BASELINE_PRESENT = PromptCraft.text(RCA, "baseline_present.md");

    /** {@code dossier/tools.md}: the MCP tools the agent may call. */
    static final String TOOLS = PromptCraft.text(RCA, "tools.md");

    static final String METHOD_FILE = "method.md";
    static final String TOOLS_FILE = "tools.md";

    /** The {@code Read first} line for {@code dossier/method.md}, added only when the classifier has a method file. */
    static final String METHOD_LINE = PromptCraft.text(RCA, "method_present.md").stripTrailing();

    /**
     * The RCA method file per built-in classifier, under {@code prompt-craft/rca/methods/}. The two drift
     * classifiers share one. A classifier missing here (a user or regex classifier) gets no method file.
     */
    private static final Map<String, String> METHOD_BY_KEY = Map.of(
            BuiltInDetector.Kind.TOOL_ERROR, "tool_error.md",
            BuiltInDetector.Kind.DURATION_DRIFT, "metric_drift.md",
            BuiltInDetector.Kind.COST_DRIFT, "metric_drift.md",
            BuiltInDetector.Kind.SECRET_LEAK, "secret_leak.md",
            BuiltInDetector.Kind.MALFORMED_OUTPUT, "malformed_output.md",
            BuiltInDetector.Kind.FRUSTRATION, "frustration.md",
            BuiltInDetector.Kind.GROUNDEDNESS, "groundedness.md");

    /** The classifier's RCA method text, or null when it has none. */
    static @Nullable String method(@Nullable String classifierKey) {
        String file = classifierKey == null ? null : METHOD_BY_KEY.get(classifierKey);
        return file == null ? null : PromptCraft.text(RCA, "methods/" + file);
    }

    /** What the run concluded — {@link RcaSynthesisOutput}-validated. */
    public record Result(
            String verdict,
            @Nullable String summary,
            /** High first, then by flagged rows explained. */
            List<Cause> causes,
            List<RuledOutCheck> ruledOut,
            @Nullable String detailedReport,
            /** Whether this run actually had the repo to read. Recorded per report, because the
             *  ceiling belongs to the run, not to whether a repo is connected when someone reads it. */
            boolean repoAvailable) {}

    /**
     * What one run investigates: every finding its case held when RCA was pressed, oldest first, and the span
     * they cover together.
     *
     * @param findingIds the findings, oldest first
     * @param onsetAt the oldest finding's onset
     * @param lastSeenAt the newest finding's last sighting
     */
    public record Subject(List<String> findingIds, String onsetAt, String lastSeenAt) {}

    /**
     * The findings' evidence as the run needs it.
     *
     * @param citableTraceIds every trace id in the findings' evidence: what a cause may cite
     * @param citableSessionIds every session id in the findings' evidence
     * @param baselinePresent whether the evidence has {@code baseline} rows to compare against
     * @param flaggedCount how many rows the findings flagged, in {@code grain}
     * @param grain {@code traces} or {@code sessions}
     */
    public record Evidence(
            Set<String> citableTraceIds,
            Set<String> citableSessionIds,
            boolean baselinePresent,
            int flaggedCount,
            String grain) {}

    private final RcaProperties props;
    private final Map<String, RcaSandbox> sandboxes;
    private final GitIntegrationRepository integrations;
    private final GitProviderFactory providers;
    private final ApiKeyService apiKeys;
    private final ObjectMapper mapper;

    public AgenticRcaEngine(
            RcaProperties props,
            List<RcaSandbox> sandboxList,
            GitIntegrationRepository integrations,
            GitProviderFactory providers,
            ApiKeyService apiKeys,
            ObjectMapper mapper) {
        this.props = props;
        Map<String, RcaSandbox> byKey = new HashMap<>();
        for (RcaSandbox s : sandboxList) byKey.put(s.key(), s);
        this.sandboxes = Map.copyOf(byKey);
        this.integrations = integrations;
        this.providers = providers;
        this.apiKeys = apiKeys;
        this.mapper = mapper;
    }

    /**
     * Fail fast at startup when {@code tessary.rca.agentic.sandbox} names no registered driver — mirrors
     * {@code AgenticDriftAnalyzer#validateSandboxConfig}. Unlike the observer's analyzer, this engine is
     * always the RCA path (there is no alternate, non-agentic analyzer to fall back to), so the check is
     * unconditional rather than gated on "am I the active analyzer".
     *
     * <p><b>This engine deliberately does not apply the same unconditional treatment to a blank {@code
     * tessary.rca.agentic.mcp-base-url} here.</b> Unlike {@code tessary.rca.agentic.sandbox}'s key (whose
     * default, "e2b", is always registered — a stable invariant), the MCP base URL has no safe non-blank
     * default, and this bean is instantiated in EVERY app-context boot regardless of whether the boot
     * ever calls RCA — no {@code application.yml} sets it, only {@code docker-compose.{yml,dev.yml}}'s
     * env injection does, so
     * a bare {@code mvn test} / non-compose boot (every {@code @SpringBootTest} in {@code app}, proven
     * concretely with {@code OpenApiSpecDriftTest}) would fail context refresh on this line alone. The
     * per-job throw in {@link #run} stays the enforcement point; a Spring-context-safe startup guard
     * would need either a real default this class cannot know (docker-compose derives it from {@code
     * SITE_DOMAIN}) or an opt-in flag nobody asked for — narrower work than this issue's scope. See the
     * localhost/blank guard actually added in {@code sandbox-runner/launcher/server.js}'s {@code
     * runAgenticScript}, which runs once per real job, not once per process boot.
     */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.getAgentic().getSandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("tessary.rca.agentic.sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    public Result run(
            RcaJobRow job, RcaReportRow report, Subject subject, Map<String, String> dossierFiles, Evidence evidence) {
        Agentic cfg = props.getAgentic();
        String mcpBase = cfg.getMcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Not a degraded run — an impossible one. The dossier carries the claim and its numbers;
            // every trace and span behind them is read through MCP, so without the door the agent can
            // only paraphrase the detector back at us.
            throw new TessaryException(
                    RcaError.NO_EVIDENCE_DOOR,
                    "tessary.rca.agentic.mcp-base-url is unset, so the agent has no way to read the evidence");
        }
        // The principal is the triggering user.
        String keyPrincipal = job.createdBy();

        // The repo is optional and read at run time rather than at the press: an integration connected
        // (or disconnected) between the two is a fact about this run, not the previous one.
        Optional<Clone> clone = clone(job.projectId());
        if (clone.isEmpty()) {
            log.info(
                    "rca agentic project={} job={} running without a repo — the code side of every cause is unread",
                    job.projectId(),
                    job.id());
        }

        String prompt = buildPrompt(
                subject,
                clone.isPresent(),
                evidence.baselinePresent(),
                dossierFiles.containsKey(METHOD_FILE),
                evidence.flaggedCount(),
                evidence.grain(),
                timeBudgetMinutes(cfg.getTimeoutMs()));

        // A short-lived project-scoped admin key, named after the job so the audit trail ties it to this
        // run. Revoked in the finally below — the key must not outlive the sandbox.
        String keyName = "rca-" + job.id();
        ApiKeyService.Issued issued = apiKeys.issue(job.projectId(), keyPrincipal, keyName, KeyScope.ADMIN);
        try {
            // validateSandboxConfig guarantees the configured key is registered.
            RcaSandbox sandbox =
                    Objects.requireNonNull(sandboxes.get(props.getAgentic().getSandbox()));
            RcaSandbox.SandboxRun run = sandbox.run(new RcaSandbox.SandboxRequest(
                    job.projectId(),
                    job.subjectId(),
                    clone.map(Clone::url).orElse(null),
                    clone.map(Clone::headSha).orElse(null),
                    clone.isPresent() ? subject.onsetAt() : null,
                    dossierFiles,
                    prompt,
                    JSON_SCHEMA,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext(),
                    report.id()));

            RcaSynthesisOutput.Parsed parsed = RcaSynthesisOutput.parse(
                    mapper,
                    run.resultText(),
                    evidence.citableTraceIds(),
                    evidence.citableSessionIds(),
                    clone.isPresent(),
                    job.projectId());
            if (parsed.detailedReport() == null) {
                // Schema-required, but a schema-ignoring model must not sink an otherwise-valid
                // verdict — fall back to the summary so the report page never renders empty.
                log.warn("rca agentic project={} job={} returned no detailed_report", job.projectId(), job.id());
            }
            log.info(
                    "rca agentic project={} job={} kind={} verdict={} verdict_corrected={} causes={} ruled_out={} repo={}",
                    job.projectId(),
                    job.id(),
                    report.reportKind(),
                    parsed.verdict(),
                    parsed.verdictNote() != null,
                    parsed.causes().size(),
                    parsed.ruledOut().size(),
                    clone.isPresent());
            String detailed = parsed.detailedReport() == null ? parsed.summary() : parsed.detailedReport();
            if (parsed.verdictNote() != null) {
                // The downgrade must be visible where the engineer reads, not only in a log line.
                detailed = detailed == null ? parsed.verdictNote() : parsed.verdictNote() + "\n\n" + detailed;
            }
            return new Result(
                    parsed.verdict(),
                    parsed.summary(),
                    parsed.causes(),
                    parsed.ruledOut(),
                    detailed,
                    clone.isPresent());
        } finally {
            revokeQuietly(issued.token().id(), keyPrincipal, job);
        }
    }

    /** The repo to hand the sandbox: an authenticated clone URL and the commit to check out. */
    private record Clone(String url, String headSha) {}

    /**
     * The project's repo, or empty when it has no integration — or when the integration is there but
     * cannot currently mint a token. A repo that will not clone is the same situation as no repo at all
     * from the agent's side, and failing the run over it would deny an evidence-only investigation that
     * was always going to be possible.
     */
    private Optional<Clone> clone(String projectId) {
        Optional<GitIntegrationRow> integ = integrations.findByProject(projectId);
        if (integ.isEmpty()) return Optional.empty();
        Optional<String> url = GitCloneUrls.authenticated(providers, integ.get());
        if (url.isEmpty()) {
            log.warn("rca agentic project={} has a git integration but could not mint a clone token", projectId);
            return Optional.empty();
        }
        return Optional.of(new Clone(
                url.get(), providers.client(integ.get().providerEnum()).resolveHeadSha(integ.get(), null)));
    }

    /** Revocation must never mask the run's own outcome — log and move on; the key also carries the
     *  audit trail either way. */
    private void revokeQuietly(String tokenId, String actor, RcaJobRow job) {
        try {
            apiKeys.revoke(tokenId, actor);
        } catch (RuntimeException e) {
            log.warn(
                    "rca agentic project={} job={} failed to revoke ephemeral MCP key {}: {}",
                    job.projectId(),
                    job.id(),
                    tokenId,
                    e.getMessage());
        }
    }

    /** The budget the agent is told: the sandbox boot and the clone spend part of the hard timeout, so
     *  telling the agent the whole window would let it run into the kill and lose every cause it has. */
    static long timeBudgetMinutes(long timeoutMs) {
        return Math.max(1, timeoutMs * 4 / 5 / 60_000);
    }

    /**
     * The one investigative prompt, filled for this run. Package-private and static so
     * {@code AgenticRcaPromptTest} can pin it without a Spring context; it reads nothing but its arguments.
     *
     * <p>{@code {onset_commit}} in the repo snippet is left in place: the sandbox resolves it after the clone,
     * from the {@code onset_at} this class sends beside the clone URL.
     */
    static String buildPrompt(
            Subject subject,
            boolean repoCloned,
            boolean baselinePresent,
            boolean methodPresent,
            int flaggedCount,
            String grain,
            long timeBudgetMinutes) {
        String prompt = PROMPT;
        prompt = baselinePresent
                ? prompt.replace("{baseline}", BASELINE_PRESENT.stripTrailing())
                : prompt.replace("{baseline}\n\n", "");
        prompt = methodPresent ? prompt.replace("{method_line}", METHOD_LINE) : prompt.replace("{method_line}\n", "");
        String ids = subject.findingIds().stream().map(id -> "`" + id + "`").collect(Collectors.joining(", "));
        return prompt.replace("{finding_ids}", ids)
                .replace("{onset}", subject.onsetAt())
                .replace("{last_seen}", subject.lastSeenAt())
                .replace("{flagged_count}", Integer.toString(flaggedCount))
                .replace("{grain}", grain)
                .replace("{time_budget_minutes}", Long.toString(timeBudgetMinutes))
                .replace("{repo}", (repoCloned ? REPO_PRESENT : REPO_ABSENT).stripTrailing());
    }
}
