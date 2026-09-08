// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import ai.tessary.evals.config.RcaProperties;
import ai.tessary.evals.config.RcaProperties.Agentic;
import ai.tessary.evals.git.GitCloneUrls;
import ai.tessary.evals.git.GitIntegrationRepository;
import ai.tessary.evals.git.GitIntegrationRow;
import ai.tessary.evals.git.GitProviderFactory;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.RcaError;
import ai.tessary.evals.prompt.PromptCraft;
import ai.tessary.evals.rca.RcaDtos.Hypothesis;
import ai.tessary.evals.rca.RcaSynthesisOutput.ChecklistAssessment;
import ai.tessary.evals.tenant.ApiKeyService;
import ai.tessary.evals.tenant.KeyScope;
import ai.tessary.evals.tenant.OrgMembership;
import ai.tessary.evals.tenant.OrgMembershipRepository;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every RCA's analysis stage: hand the investigation to an agent session in a fresh E2B microVM (via
 * {@link E2bRcaSandbox}) with three sources of evidence:
 *
 * <ol>
 *   <li>the finding's dossier as files — the claim, the detector's own numbers, the measured checklist
 *       (assembled by {@link RcaAnalysisService});</li>
 *   <li>a live MCP connection back to this platform, authed with a short-lived key minted for the run
 *       and revoked in a {@code finally}. <b>Required</b>: the dossier states the claim and the
 *       substrate behind it is read on demand, so a run without the door cannot open a single row of
 *       the evidence it is investigating;</li>
 *   <li>the project repo, cloned at the integration's default-branch HEAD, <b>when there is one</b>.
 *       The repo is what turns "the outputs changed" into "this commit changed them", and it deepens
 *       every run that has it — but an evidence-only project still gets an investigation, with a
 *       ceiling: it can show what changed in production and not what changed in the code.</li>
 * </ol>
 *
 * <p>The agent is also the judge of {@link RcaChecklist}'s measurements: the prompt walks it through each
 * check and demands an explicit assessment, because the fixed thresholds this replaced ruled causes in
 * and out wrongly often enough to poison the verdict. Its output still goes through {@link
 * RcaSynthesisOutput} receipt-validation, so neither its trace citations nor its checklist assessments
 * can reference anything that was not actually measured — and a comparative verdict that cited no
 * baseline-side evidence is downgraded rather than persisted.
 *
 * <p><b>Nothing in the prompt discloses that a triage pass ran on this finding</b> — see the firewall
 * section of {@link RcaAnalysisService}. Not the ruling, not its summary, not its existence.
 */
@Service
public class AgenticRcaEngine {

    private static final Logger log = LoggerFactory.getLogger(AgenticRcaEngine.class);

    /** A side this thin makes a percentage a handful of individual traces rather than a rate. */
    private static final int SMALL_SIDE = 5;

    /** The agent's final-output contract: verdict, hypotheses with receipts, one assessment per
     *  measured checklist id, and the markdown writeup. */
    /** Where this lane's prose lives: {@code prompt-craft/rca/}. */
    private static final String RCA = "rca";

    static final String JSON_SCHEMA = PromptCraft.text(RCA, "response_schema.json");

    /**
     * The eight rules of a root-cause investigation, verbatim in the prompt.
     *
     * <p>They are the agreed specification for this layer, not prompt tuning: what an RCA is for and what
     * it is allowed to conclude (rules 1 and 2), the order that keeps it cheap and honest (3, 4), the
     * standard of proof (5), what the repository is and is not (6), the obligation that makes a
     * report actionable rather than a story (7), and — since B (#994) — what to do if the run is forced
     * to stop before finishing (8), so a truncated run reports a low-confidence verdict rather than
     * silence.
     */
    private static final String RULES = PromptCraft.text(RCA, "rules.md");

    /**
     * How the agent reaches the substrate. Names the tools it may actually call — a tool named here that
     * the surface does not register costs a turn on {@code unknown tool}, and one the surface has that is
     * not named here is one it will not think to use.
     */
    private static final String MCP_DOOR = PromptCraft.text(RCA, "mcp_door.md");

    /** What the run concluded — {@link RcaSynthesisOutput}-validated. */
    public record Result(
            String verdict,
            String summary,
            List<Hypothesis> hypotheses,
            List<ChecklistAssessment> checklist,
            String detailedReport) {}

    private final RcaProperties props;
    private final Map<String, RcaSandbox> sandboxes;
    private final GitIntegrationRepository integrations;
    private final GitProviderFactory providers;
    private final ApiKeyService apiKeys;
    private final ProjectRepository projects;
    private final OrgMembershipRepository memberships;
    private final ObjectMapper mapper;

    public AgenticRcaEngine(
            RcaProperties props,
            List<RcaSandbox> sandboxList,
            GitIntegrationRepository integrations,
            GitProviderFactory providers,
            ApiKeyService apiKeys,
            ProjectRepository projects,
            OrgMembershipRepository memberships,
            ObjectMapper mapper) {
        this.props = props;
        Map<String, RcaSandbox> byKey = new HashMap<>();
        for (RcaSandbox s : sandboxList) byKey.put(s.key(), s);
        this.sandboxes = Map.copyOf(byKey);
        this.integrations = integrations;
        this.providers = providers;
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.memberships = memberships;
        this.mapper = mapper;
    }

    /**
     * Fail fast at startup when {@code evals.rca.agentic.sandbox} names no registered driver — mirrors
     * {@code AgenticDriftAnalyzer#validateSandboxConfig}. Unlike the observer's analyzer, this engine is
     * always the RCA path (there is no alternate, non-agentic analyzer to fall back to), so the check is
     * unconditional rather than gated on "am I the active analyzer".
     *
     * <p><b>#857 considered and rejected adding the same unconditional treatment to a blank {@code
     * evals.rca.agentic.mcp-base-url} here.</b> Unlike {@code evals.rca.agentic.sandbox}'s key (whose
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
            throw new IllegalStateException("evals.rca.agentic.sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    public Result run(
            RcaJobRow job,
            RcaReportRow report,
            String findingId,
            Map<String, String> dossierFiles,
            Set<String> baselineTraceIds,
            Set<String> flaggedTraceIds,
            Set<String> measuredChecks) {
        Agentic cfg = props.getAgentic();
        String mcpBase = cfg.getMcpBaseUrl();
        if (mcpBase == null || mcpBase.isBlank()) {
            // Not a degraded run — an impossible one. The dossier carries the claim and its numbers;
            // every trace and span behind them is read through MCP, so without the door the agent can
            // only paraphrase the detector back at us.
            throw new EvalsException(
                    RcaError.NO_EVIDENCE_DOOR,
                    "evals.rca.agentic.mcp-base-url is unset, so the agent has no way to read the evidence");
        }
        // The principal is the triggering user; a system-triggered run (no created_by) borrows the org's
        // owner, so the key can always be minted and attributed.
        String keyPrincipal = job.createdBy() != null ? job.createdBy() : orgOwnerPrincipal(job.projectId());
        if (keyPrincipal == null) {
            throw new EvalsException(RcaError.NO_EVIDENCE_DOOR, "the project's org has no owner to issue a key to");
        }

        // The repo is optional and read at run time rather than at the press: an integration connected
        // (or disconnected) between the two is a fact about this run, not the previous one.
        Optional<Clone> clone = clone(job.projectId());
        if (clone.isEmpty()) {
            log.info(
                    "rca agentic project={} job={} running without a repo — the code side of every cause is unread",
                    job.projectId(),
                    job.id());
        }

        // A short-lived project-scoped admin key, named after the job so the audit trail ties it to this
        // run. Revoked in the finally below — the key must not outlive the sandbox.
        String keyName = "rca-" + job.id() + (job.createdBy() == null ? " (system)" : "");
        ApiKeyService.Issued issued = apiKeys.issue(job.projectId(), keyPrincipal, keyName, KeyScope.ADMIN);
        try {
            String sandboxKey = props.getAgentic().getSandbox();
            RcaSandbox sandbox = sandboxes.get(sandboxKey);
            if (sandbox == null) {
                // The boot-time validator already guarantees this is registered — this is the same
                // belt-and-suspenders defensive guard AgenticDriftAnalyzer's callers use, not a path
                // expected to trip in practice.
                throw new EvalsException(RcaError.UPSTREAM_FAILED, "unknown RCA sandbox '" + sandboxKey + "'");
            }
            RcaSandbox.SandboxRun run = sandbox.run(new RcaSandbox.SandboxRequest(
                    job.projectId(),
                    job.subjectId(),
                    clone.map(Clone::url).orElse(null),
                    clone.map(Clone::headSha).orElse(null),
                    dossierFiles,
                    buildPrompt(report, findingId, clone.isPresent(), baselineTraceIds.size(), flaggedTraceIds.size()),
                    JSON_SCHEMA,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext(),
                    report.id()));

            RcaSynthesisOutput.Parsed parsed = RcaSynthesisOutput.parse(
                    mapper, run.resultText(), baselineTraceIds, flaggedTraceIds, measuredChecks, job.projectId());
            if (parsed.detailedReport() == null) {
                // Schema-required, but a schema-ignoring model must not sink an otherwise-valid
                // verdict — fall back to the summary so the report page never renders empty.
                log.warn("rca agentic project={} job={} returned no detailed_report", job.projectId(), job.id());
            }
            log.info(
                    "rca agentic project={} job={} verdict={} downgraded={} hypotheses={} assessed={}/{} repo={}",
                    job.projectId(),
                    job.id(),
                    parsed.verdict(),
                    parsed.verdictNote() != null,
                    parsed.hypotheses().size(),
                    parsed.checklist().size(),
                    measuredChecks.size(),
                    clone.isPresent());
            String detailed = parsed.detailedReport() == null ? parsed.summary() : parsed.detailedReport();
            if (parsed.verdictNote() != null) {
                // The downgrade must be visible where the engineer reads, not only in a log line.
                detailed = parsed.verdictNote() + "\n\n" + detailed;
            }
            return new Result(parsed.verdict(), parsed.summary(), parsed.hypotheses(), parsed.checklist(), detailed);
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

    /** The org owner who stands in as key principal for a system-triggered run — deterministic
     *  (earliest membership wins) so repeated runs attribute the same way. Null when the org has no
     *  owner at all, which should not happen for a project that can trigger an RCA. */
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

    /** Revocation must never mask the run's own outcome — log and move on; the key also carries the
     *  audit trail either way. */
    private void revokeQuietly(String tokenId, @Nullable String actor, RcaJobRow job) {
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

    /** Package-private and static so {@code AgenticRcaPromptTest} can pin what the prompt tells the agent is
     *  callable — the MCP paragraph names tools, and a tool named here that the surface has removed costs the
     *  agent a turn on {@code unknown tool}. It reads nothing but its arguments. */
    static String buildPrompt(
            RcaReportRow report, String findingId, boolean repoCloned, int baselineTraces, int flaggedTraces) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a root-cause analyst embedded in an LLM-evaluation platform. A classifier filed a")
                .append(" FINDING: it saw something in production it believes is a deviation. Nobody has looked")
                .append(" at it yet. Your job is to find out WHY it happened — which means locating the CHANGE")
                .append(" behind it — and to PROVE it with evidence you can cite. A confident wrong RCA is worse")
                .append(" than an honest \"no change located\": the engineer who reads it will act on it.\n\n");

        sb.append("## What you have\n\n")
                .append("`dossier/finding.md` — what the detector asserts, over what, since when, and how many")
                .append(" evidence refs it recorded per role.\n")
                .append("`dossier/evidence.json` — its own numbers, verbatim.\n")
                .append("`dossier/checklist.md` — the structural checks, MEASURED BUT NOT JUDGED (see below).\n\n")
                .append("The finding id is `")
                .append(findingId)
                .append("`, and its window runs ")
                .append(report.windowFrom())
                .append(" → ")
                .append(report.windowTo())
                .append(" with onset at ")
                .append(report.windowSplit())
                .append(".\n\n");

        sb.append(MCP_DOOR).append('\n');

        sb.append("## The repository\n\n");
        if (repoCloned) {
            sb.append("`./repo/` is a clone of the project's repository at its current HEAD (run git as")
                    .append(" `git -C ./repo ...`). The committed .tessary/ bundle, when present, describes the")
                    .append(" LLM call sites under evaluation (repo/.tessary/pipeline/call_sites/**/*.yaml —")
                    .append(" intent, prompts, surrounding code) and the graders")
                    .append(" (repo/.tessary/graders/**/*.yaml). Use it to connect trace-level evidence to code:")
                    .append(" read the call site's prompt, template and tool definitions, follow the execution")
                    .append(" path, and walk `git log` around the onset (")
                    .append(report.windowSplit())
                    .append(") for commits that plausibly altered the behaviour. NOTE: HEAD may postdate the")
                    .append(" finding; use history, do not assume HEAD is the state that produced it.\n\n");
        } else {
            sb.append("There is none. This project has no repository connected, so there is no `./repo/` and no")
                    .append(" commit history to walk. That lowers the ceiling on this run and does not stop it:")
                    .append(" establish what changed in production and when, from the traces themselves, and")
                    .append(" state plainly in the report that the code side is unread. Do NOT assert anything")
                    .append(" about a prompt, a config or a commit you had no way to open.\n\n");
        }

        sb.append("THE CHECKLIST IS YOURS TO JUDGE. dossier/checklist.md carries raw numbers only — no")
                .append(" thresholds were applied and nothing has been ruled in or out. This is deliberate:")
                .append(" this system used to apply fixed cut-offs and they were confidently wrong far too")
                .append(" often. A 20%-traffic canary model was called a model change when it never served the")
                .append(" failing traces, and a concentration in one cohort was called a cause when the same")
                .append(" value was just as common on the baseline side. For EACH check id in")
                .append(" dossier/checklist.md,")
                .append(" return exactly one entry in `checklist` with your own assessment:\n")
                .append("- serving_model: a new model only matters if it actually serves the FLAGGED traces —")
                .append(" check them, not the project-wide share.\n")
                .append("- failing_cohort_shape: a concentration is a lead, not a conclusion; check whether the")
                .append(" concentrated value is also over-represented on the baseline side.\n")
                .append("Use `ruled_out` when you checked and it explains none of the movement, `contributing`")
                .append(" when it is part of the story, `explains` when it accounts for the movement on its own,")
                .append(" and `unknown` when the evidence genuinely does not settle it — `unknown` is an honest")
                .append(" answer, a fabricated confident one is not.\n\n");

        sb.append(RULES).append('\n');

        sb.append("BURDEN OF PROOF — the verdict must be demonstrated, not inferred:\n")
                .append("- Every claim about what a side contained must cite a trace id you actually fetched")
                .append(" through MCP, or a repo artifact. If you catch yourself writing \"must have been\" or")
                .append(" \"presumably\", stop and fetch the evidence. If it cannot be read, say so and weigh")
                .append(" `inconclusive` — never paper over the gap with an inference.\n")
                .append("- Hypotheses may cite ONLY trace ids that appear in this finding's own evidence refs")
                .append(" (either side); an id outside them is discarded as hallucinated.\n")
                .append("- traffic_shift claims the WORK changed, not the behaviour. Prove both halves:")
                .append(" characterize the baseline side's traces AND the flagged side's from their actual")
                .append(" content, then show the composition difference concretely (task kinds with counts")
                .append(" per side). Cite trace ids from BOTH sides.\n")
                .append("- behavior_change claims the outputs regressed on comparable work. Show a matched")
                .append(" pair: comparable inputs on the two sides with different outcomes, citing at least")
                .append(" one baseline-side and one flagged-side trace id.\n")
                .append("- definition_change: cite the exact commit sha and the diff hunk that changed the")
                .append(" criteria.\n")
                .append("- model_change: cite which model serves the flagged traces and which served the")
                .append(" baseline ones.\n")
                .append("The platform enforces the comparative half of this: a traffic_shift or")
                .append(" behavior_change verdict whose hypotheses cite no baseline-side trace id is downgraded")
                .append(" to inconclusive on receipt.\n\n");

        sb.append("SAMPLE SIZE: this finding cites ")
                .append(baselineTraces)
                .append(" baseline-side and ")
                .append(flaggedTraces)
                .append(" flagged-side trace(s).");
        if (baselineTraces < SMALL_SIDE || flaggedTraces < SMALL_SIDE) {
            sb.append(" At that size any rate you compute is a handful of individual traces, not a rate —")
                    .append(" read and name each one individually, cap every hypothesis's confidence at")
                    .append(" \"medium\", and state the sufficiency limit plainly in the report.");
        }
        sb.append(" A side with zero refs is a real state for several detectors (their reference is a fitted")
                .append(" model, not a set of rows): read it as \"no enumerable baseline\" and say so, rather")
                .append(" than characterizing a side you never saw.\n\n");

        sb.append("METHOD: work evidence-first and rule-outs-first. Read the three dossier files, call")
                .append(" get_finding_evidence with count_only to see what you are dealing with, then page the")
                .append(" refs and read traces on BOTH sides — the baseline side is what makes a difference a")
                .append(" difference. Settle the cheap structural checks before reaching for anything")
                .append(" expensive")
                .append(repoCloned ? ", then walk the code for the change." : ".")
                .append(" You run against a hard wall-clock budget — go deep on the strongest signal, not wide")
                .append(" on everything.\n\n");

        sb.append("Produce 1-4 hypotheses for what the classifier saw, most likely first, each citing the")
                .append(" trace ids that best evidence it (comparative verdicts need both sides). Set verdict to")
                .append(" \"behavior_change\" when the outputs genuinely changed/regressed, \"traffic_shift\"")
                .append(" when the flagged cohort is dominated by a different kind of traffic rather than worse")
                .append(" behaviour, \"definition_change\" when you VERIFIED that the grader's criteria changed,")
                .append(" \"model_change\" when you VERIFIED that a different serving model is behind the")
                .append(" flagged traces, and \"inconclusive\" when you located no change — including when the")
                .append(" honest reading is that nothing happened here and the detector fired on a population")
                .append(" that did not move. Say which of those two an `inconclusive` is; they are different")
                .append(" answers and the reader needs to know which one you reached. The verdict should agree")
                .append(" with your checklist: if you marked a check `explains`, the verdict is normally that")
                .append(" check's cause. The `detailed_report` field is your full investigation as markdown:")
                .append(" what you checked in the traces")
                .append(repoCloned ? " and in the code (with file paths and commit shas)" : "")
                .append(", how you settled each checklist item, and how the evidence supports the verdict —")
                .append(" written for the engineer who owns this call site. END it with an \"## Evidence audit\"")
                .append(" section (a markdown table mapping every load-bearing claim to its evidence: trace id,")
                .append(" repo path/commit, or query — a claim you cannot put in that table does not belong in")
                .append(" the report) followed by a \"## What would settle it\" section naming the one")
                .append(" experiment that would confirm or refute your leading hypothesis.\n\n");

        sb.append("Finish by returning the JSON object required by the schema, and nothing else — the harness")
                .append(" collects it through the structured-output tool, so do not wrap it in prose.");
        return sb.toString();
    }
}
