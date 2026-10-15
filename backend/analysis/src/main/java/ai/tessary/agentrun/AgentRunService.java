// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitCloneUrls;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.llm.AgenticCredentialResolver;
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
 * The ceremony around one agent run: resolve model and credential, mint the run's MCP key, find the repository, call
 * the {@link AgentRunSandbox}. The key is issued to the org's owner and revoked in a {@code finally}, as is the
 * credential. A missing or unmintable repository runs without {@code ./repo/} rather than failing.
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final Map<String, AgentRunSandbox> sandboxes;
    private final AgentRunProperties props;
    private final RcaProperties rcaProps;
    private final AgentModels models;
    private final AgenticCredentialResolver credentials;
    private final ApiKeyService apiKeys;
    private final ProjectRepository projects;
    private final OrgMembershipRepository memberships;
    private final GitIntegrationRepository integrations;
    private final GitProviderFactory providers;

    public AgentRunService(
            List<AgentRunSandbox> sandboxList,
            AgentRunProperties props,
            RcaProperties rcaProps,
            AgentModels models,
            AgenticCredentialResolver credentials,
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
        this.models = models;
        this.credentials = credentials;
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
     * @param files reachable as {@code ./dossier/<path>} @param subject the ledger row's subject and the run's key name
     * @throws TessaryException {@code AgentRunError.*}, or {@code ModelConfigError.MISSING_CREDENTIALS}
     */
    public AgentRunSandbox.Result run(
            String projectId,
            ModelLane lane,
            String systemPrompt,
            String prompt,
            Map<String, String> files,
            @Nullable String jsonSchema,
            LlmUsageAccountant.Subject subject) {
        String mcpUrl = platformMcpUrl();
        AgentModels.Choice choice = models.resolve(projectId, lane);
        // Before the key is minted, so a missing credential leaves nothing to revoke.
        AgenticCredentialResolver.Credential credential = credentials.resolve(projectId, choice.provider());
        try {
            return runWithCredential(
                    projectId, lane, systemPrompt, prompt, files, jsonSchema, subject, mcpUrl, choice, credential);
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
            String mcpUrl,
            AgentModels.Choice choice,
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
                    choice.model(),
                    choice.modelCost(),
                    choice.provider().name(),
                    credential,
                    mcpUrl,
                    issued.plaintext(),
                    props.getTimeoutMs(),
                    props.getMaxTurns(),
                    subject));
        } finally {
            revokeQuietly(issued.token().id(), projectId, lane, subject);
        }
    }

    /**
     * This lane's origin, else RCA's, plus {@code /mcp}. @throws TessaryException
     * {@code AgentRunError.LAUNCHER_MISCONFIGURED} when both are unset
     */
    public String platformMcpUrl() {
        String own = props.getMcpBaseUrl();
        String rca = rcaProps.getAgentic().getMcpBaseUrl();
        String origin = own != null && !own.isBlank() ? own : rca == null ? "" : rca;
        if (origin.isBlank()) {
            throw new TessaryException(
                    AgentRunError.LAUNCHER_MISCONFIGURED,
                    "tessary.agentrun.mcp-base-url and tessary.rca.agentic.mcp-base-url are both unset, so the"
                            + " agent has no way to read the traces");
        }
        return origin.replaceAll("/+$", "") + "/mcp";
    }

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

    /** Revocation must never mask the run's own outcome. */
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
