// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.agentrun.StepWords;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingEvidenceRow;
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
import ai.tessary.rca.RcaDtos.Coverage;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
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
 * The MCP door is required: the dossier states the claim and the evidence is read on demand. The repo is optional.
 * Output goes through {@link RcaSynthesisOutput} receipt-validation, so no cause can cite an id outside the evidence.
 * Nothing in the prompt discloses that triage ran (see {@link RcaAnalysisService}'s firewall).
 */
@Service
public class AgenticRcaEngine {

    private static final Logger log = LoggerFactory.getLogger(AgenticRcaEngine.class);

    private static final String RCA = "rca";

    static final String JSON_SCHEMA = PromptCraft.text(RCA, "response_schema.json");

    static final String SYSTEM_PROMPT = PromptCraft.text(RCA, "system_prompt.md");

    private static final String PROMPT = PromptCraft.text(RCA, "prompt.md");
    private static final String REPO_PRESENT = PromptCraft.text(RCA, "repo_present.md");
    private static final String REPO_ABSENT = PromptCraft.text(RCA, "repo_absent.md");
    private static final String BASELINE_PRESENT = PromptCraft.text(RCA, "baseline_present.md");

    static final String TOOLS = PromptCraft.text(RCA, "tools.md");

    static final String METHOD_FILE = "method.md";
    static final String TOOLS_FILE = "tools.md";

    static final String METHOD_LINE = PromptCraft.text(RCA, "method_present.md").stripTrailing();

    /** The two drift classifiers share one method file. */
    private static final Map<String, String> METHOD_BY_KEY = Map.of(
            BuiltInDetector.Kind.TOOL_ERROR, "tool_error.md",
            BuiltInDetector.Kind.DURATION_DRIFT, "metric_drift.md",
            BuiltInDetector.Kind.COST_DRIFT, "metric_drift.md",
            BuiltInDetector.Kind.SECRET_LEAK, "secret_leak.md",
            BuiltInDetector.Kind.MALFORMED_OUTPUT, "malformed_output.md",
            BuiltInDetector.Kind.FRUSTRATION, "frustration.md",
            BuiltInDetector.Kind.GROUNDEDNESS, "groundedness.md");

    static @Nullable String method(@Nullable String classifierKey) {
        String file = classifierKey == null ? null : METHOD_BY_KEY.get(classifierKey);
        return file == null ? null : PromptCraft.text(RCA, "methods/" + file);
    }

    public record Result(
            String verdict,
            @Nullable String summary,
            List<Cause> causes,
            List<RuledOutCheck> ruledOut,
            @Nullable String detailedReport,
            /**
             * Recorded per report, because the ceiling belongs to the run, not to whether a repo is connected later.
             */
            boolean repoAvailable,
            @Nullable Coverage coverage) {}

    public record Subject(List<String> findingIds, String onsetAt, String lastSeenAt) {}

    /**
     * A cause cites only flagged rows: a comparison row proves nothing about the flagged ones, so a cited comparison id
     * is dropped.
     *
     * @param baselinePresent a {@code baseline} row is on the comparison side, which unflagged members alone are not
     * @param recordedByRole each role's count as {@code finding.md} showed it, summed over the subject's findings
     */
    public record Evidence(
            Set<String> flaggedTraceIds,
            Set<String> flaggedSessionIds,
            Set<String> comparisonTraceIds,
            Set<String> comparisonSessionIds,
            boolean baselinePresent,
            Map<String, Long> recordedByRole,
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
     * Unconditional because RCA has no non-agentic path. A blank mcp-base-url is not checked here: no
     * {@code application.yml} sets it, so every non-compose {@code @SpringBootTest} would fail context refresh.
     * {@link #run} enforces it per job.
     */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.agentic().sandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("tessary.rca.agentic.sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    public Result run(
            RcaJobRow job, RcaReportRow report, Subject subject, Map<String, String> dossierFiles, Evidence evidence) {
        Agentic cfg = props.agentic();
        String mcpBase = cfg.mcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Not a degraded run but an impossible one: without MCP the agent can only paraphrase the detector.
            throw new TessaryException(
                    RcaError.NO_EVIDENCE_DOOR,
                    "tessary.rca.agentic.mcp-base-url is unset, so the agent has no way to read the evidence");
        }
        String keyPrincipal = job.createdBy();

        // Read at run time, not at the press: a connection change in between is a fact about this run.
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
                timeBudgetMinutes(cfg.timeoutMs()));

        // Named after the job for the audit trail; revoked in the finally so it never outlives the sandbox.
        String keyName = "rca-" + job.id();
        ApiKeyService.Issued issued = apiKeys.issue(job.projectId(), keyPrincipal, keyName, KeyScope.ADMIN);
        try {
            RcaSandbox sandbox =
                    Objects.requireNonNull(sandboxes.get(props.agentic().sandbox()));
            RcaSandbox.SandboxRun run = sandbox.run(new RcaSandbox.SandboxRequest(
                    job.projectId(),
                    job.subjectId(),
                    clone.map(Clone::url).orElse(null),
                    clone.map(Clone::headSha).orElse(null),
                    clone.isPresent() ? subject.onsetAt() : null,
                    dossierFiles,
                    SYSTEM_PROMPT,
                    prompt,
                    JSON_SCHEMA,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext(),
                    report.id(),
                    job.id(),
                    stepWords(job.metric(), evidence)));

            RcaSynthesisOutput.Parsed parsed =
                    RcaSynthesisOutput.parse(mapper, run.resultText(), evidence, clone.isPresent(), job.projectId());
            if (parsed.detailedReport() == null) {
                // A schema-ignoring model must not sink an otherwise-valid verdict.
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
            // A correction must be visible where the engineer reads, not only in a log line.
            List<String> notes = new ArrayList<>();
            if (parsed.verdictNote() != null) notes.add(parsed.verdictNote());
            notes.addAll(parsed.platformNotes());
            if (detailed != null) notes.add(detailed);
            detailed = notes.isEmpty() ? null : String.join("\n\n", notes);
            return new Result(
                    parsed.verdict(),
                    parsed.summary(),
                    parsed.causes(),
                    parsed.ruledOut(),
                    detailed,
                    clone.isPresent(),
                    parsed.coverage());
        } finally {
            revokeQuietly(issued.token().id(), keyPrincipal, job);
        }
    }

    /**
     * What the run's steps call the evidence rows and how many are flagged. A job's metric is its finding's classifier
     * key.
     */
    static StepWords.Context stepWords(String classifierKey, Evidence evidence) {
        Map<String, String> nouns =
                switch (classifierKey) {
                    case BuiltInDetector.Kind.GROUNDEDNESS ->
                        Map.of(FindingEvidenceRow.Role.WITNESS, "flagged answers");
                    case BuiltInDetector.Kind.FRUSTRATION -> Map.of(FindingEvidenceRow.Role.WITNESS, "flagged turns");
                    default -> Map.of();
                };
        return new StepWords.Context(nouns, Map.of(FindingEvidenceRow.Role.WITNESS, (long) evidence.flaggedCount()));
    }

    private record Clone(String url, String headSha) {}

    /** A repo that will not clone is the same as no repo, and must not deny an evidence-only investigation. */
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

    /** Revocation must never mask the run's own outcome. */
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

    /**
     * Boot and clone spend part of the hard timeout; telling the agent the whole window would let it run into the kill.
     */
    static long timeBudgetMinutes(long timeoutMs) {
        return Math.max(1, timeoutMs * 4 / 5 / 60_000);
    }

    /**
     * Only this run's facts: the instructions are in {@link #SYSTEM_PROMPT}, so they stay cacheable. {@code
     * {onset_commit}} is left in place: the sandbox resolves it after the clone.
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
                : prompt.replace("\n\n{baseline}", "");
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
