// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitCloneUrls;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelCatalog;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.usage.LlmUsageAccountant;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Everything around one agent run that a caller should not have to repeat: resolve the lane's
 * model and the org's credential for it, mint the run's MCP key and revoke it afterwards, find the
 * project's repository when it has one, and hand all of it to the configured
 * {@link AgentRunSandbox}. The caller brings the prompts, the files, the answer's schema and the
 * subject the spend is booked against, and gets the agent's answer back.
 *
 * <p>The MCP key is issued to the project org's owner (earliest membership first), the way the
 * triage engine issues its own, and revoked in a {@code finally}: it must not outlive the sandbox.
 * The credential is released the same way. The repository is optional and read at run time: a
 * project with no integration, or one that cannot mint a clone token right now, runs without
 * {@code ./repo/} rather than not at all.
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final Map<String, AgentRunSandbox> sandboxes;
    private final AgentRunProperties props;
    private final RcaProperties rcaProps;
    private final ObserverProperties observerProps;
    private final ProjectModelSettings modelSettings;
    private final AgenticCredentialResolver credentials;
    private final ModelsDevRates rates;
    private final ApiKeyService apiKeys;
    private final ProjectRepository projects;
    private final OrgMembershipRepository memberships;
    private final GitIntegrationRepository integrations;
    private final GitProviderFactory providers;

    public AgentRunService(
            List<AgentRunSandbox> sandboxList,
            AgentRunProperties props,
            RcaProperties rcaProps,
            ObserverProperties observerProps,
            ProjectModelSettings modelSettings,
            AgenticCredentialResolver credentials,
            ModelsDevRates rates,
            ApiKeyService apiKeys,
            ProjectRepository projects,
            OrgMembershipRepository memberships,
            GitIntegrationRepository integrations,
            GitProviderFactory providers) {
        Map<String, AgentRunSandbox> byKey = new HashMap<>();
        for (AgentRunSandbox s : sandboxList) byKey.put(s.key(), s);
        this.sandboxes = Map.copyOf(byKey);
        this.props = props;
        this.rcaProps = rcaProps;
        this.observerProps = observerProps;
        this.modelSettings = modelSettings;
        this.credentials = credentials;
        this.rates = rates;
        this.apiKeys = apiKeys;
        this.projects = projects;
        this.memberships = memberships;
        this.integrations = integrations;
        this.providers = providers;
    }

    /** Fail at boot when {@code tessary.agentrun.sandbox} names no registered driver. */
    @PostConstruct
    void validateSandboxConfig() {
        String key = props.getSandbox();
        if (!sandboxes.containsKey(key)) {
            throw new IllegalStateException("tessary.agentrun.sandbox='" + key
                    + "' is not a registered sandbox (known: " + sandboxes.keySet() + ")");
        }
    }

    /**
     * Run the agent once for {@code projectId} on {@code lane}.
     *
     * @param systemPrompt the agent's system prompt
     * @param prompt the task
     * @param files relative path to content, reachable by the agent as {@code ./dossier/<path>}
     * @param jsonSchema schema the final answer must satisfy, or null for a prose answer
     * @param subject what the spend is for: the ledger row's subject, and the run's key name
     * @throws TessaryException {@code AgentRunError.*} for every way the run can fail, and
     *     {@code ModelConfigError.MISSING_CREDENTIALS} when the org holds no key for the lane's provider
     */
    public AgentRunSandbox.Result run(
            String projectId,
            ModelLane lane,
            String systemPrompt,
            String prompt,
            Map<String, String> files,
            @Nullable String jsonSchema,
            LlmUsageAccountant.Subject subject) {
        String mcpBase = mcpBaseUrl();
        if (mcpBase.isBlank()) {
            throw new TessaryException(
                    AgentRunError.LAUNCHER_MISCONFIGURED,
                    "tessary.agentrun.mcp-base-url and tessary.rca.agentic.mcp-base-url are both unset, so the"
                            + " agent has no way to read the traces");
        }
        Optional<ProjectModelSettings.ResolvedAgenticModel> resolved =
                modelSettings.resolveAgenticModel(projectId, lane);
        String model = resolved.map(ProjectModelSettings.ResolvedAgenticModel::modelId)
                .orElseGet(() -> observerProps.getAgentic().getModel());
        // The deployment default is a Bedrock inference-profile id, so its rates are looked up on Bedrock.
        String modelsDevId = resolved.isPresent()
                ? resolved.get().modelsDevId()
                : ModelCatalog.modelsDevId(ModelProvider.BEDROCK, model).orElse(null);
        ModelsDevRates.ModelCost modelCost = rates.cost(modelsDevId).orElse(null);
        ModelProvider provider = resolved.map(ProjectModelSettings.ResolvedAgenticModel::provider)
                .orElse(ModelProvider.BEDROCK);
        // Resolved before the key is minted: an org with no credential fails closed with nothing to revoke.
        AgenticCredentialResolver.Credential credential = credentials.resolve(projectId, provider);
        try {
            return runWithCredential(
                    projectId,
                    lane,
                    systemPrompt,
                    prompt,
                    files,
                    jsonSchema,
                    subject,
                    mcpBase,
                    model,
                    modelCost,
                    provider,
                    credential);
        } finally {
            credentials.release(credential);
        }
    }

    private AgentRunSandbox.Result runWithCredential(
            String projectId,
            ModelLane lane,
            String systemPrompt,
            String prompt,
            Map<String, String> files,
            @Nullable String jsonSchema,
            LlmUsageAccountant.Subject subject,
            String mcpBase,
            String model,
            ModelsDevRates.@Nullable ModelCost modelCost,
            ModelProvider provider,
            AgenticCredentialResolver.Credential credential) {
        String principal = orgOwnerPrincipal(projectId);
        if (principal == null) {
            throw new TessaryException(AgentRunError.RUN_FAILED, "the project's org has no owner to issue a key to");
        }
        String keyName = lane.wire() + "-" + subject.id() + " (system)";
        ApiKeyService.Issued issued = apiKeys.issue(projectId, principal, keyName, KeyScope.ADMIN);
        try {
            Optional<Clone> clone = clone(projectId);
            AgentRunSandbox sandbox = Objects.requireNonNull(sandboxes.get(props.getSandbox()));
            return sandbox.run(new AgentRunSandbox.Request(
                    projectId,
                    lane.wire(),
                    systemPrompt,
                    prompt,
                    files,
                    jsonSchema,
                    clone.map(Clone::url).orElse(null),
                    clone.map(Clone::headSha).orElse(null),
                    model,
                    modelCost,
                    provider.name(),
                    credential,
                    mcpBase.replaceAll("/+$", "") + "/mcp",
                    issued.plaintext(),
                    props.getTimeoutMs(),
                    props.getMaxTurns(),
                    subject));
        } finally {
            revokeQuietly(issued.token().id(), projectId, lane, subject);
        }
    }

    /** This lane's own MCP origin, else RCA's, the same public origin every agent lane calls back on. */
    private String mcpBaseUrl() {
        String own = props.getMcpBaseUrl();
        if (own != null && !own.isBlank()) return own;
        String rca = rcaProps.getAgentic().getMcpBaseUrl();
        return rca == null ? "" : rca;
    }

    /** The repo to hand the sandbox: an authenticated clone URL and the commit to check out. */
    private record Clone(String url, String headSha) {}

    private Optional<Clone> clone(String projectId) {
        Optional<GitIntegrationRow> integ = integrations.findByProject(projectId);
        if (integ.isEmpty()) return Optional.empty();
        Optional<String> url = GitCloneUrls.authenticated(providers, integ.get());
        if (url.isEmpty()) {
            StructuredLog.warn(log, Markers.OPS, "agentrun.clone-token-unavailable")
                    .message("project has a git integration but could not mint a clone token; running without the repo")
                    .field("project", projectId)
                    .log();
            return Optional.empty();
        }
        return Optional.of(new Clone(
                url.get(), providers.client(integ.get().providerEnum()).resolveHeadSha(integ.get(), null)));
    }

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
    private void revokeQuietly(String tokenId, String projectId, ModelLane lane, LlmUsageAccountant.Subject subject) {
        try {
            apiKeys.revoke(tokenId);
        } catch (RuntimeException e) {
            StructuredLog.warn(log, Markers.OPS, "agentrun.key-revoke-failed")
                    .message("the run's MCP key could not be revoked; it stays live until someone revokes it")
                    .field("project", projectId)
                    .field("lane", lane.wire())
                    .field("subjectKind", subject.kind())
                    .field("subjectId", subject.id())
                    .field("token", tokenId)
                    .field("error", e.getMessage())
                    .log();
        }
    }
}
