// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.gate;

import ai.tessary.evals.config.PreDeployProperties;
import ai.tessary.evals.gate.PreDeployCheckDtos.PreDeployCheckView;
import ai.tessary.evals.model.Severity;
import ai.tessary.evals.model.TouchedSurface;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.PreDeployError;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.tenant.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The production-signal → pre-deploy loop. Closes the loop: when a
 * NEW production signal is discovered (the {@code ClassifierWorker} sweep writes a fresh detection {@code verdict}
 * with {@code source='automatic'}), this service registers a durable, surface-scoped
 * {@link PreDeployCheckRow} so a FUTURE PR touching those
 * surfaces is checked pre-merge — via the existing risk routing / risk forecast read path,
 * which unions {@link #activeSurfaces} into the change's resolved surfaces.
 *
 * <h2>Signal → surface mapping (honest, not fabricated)</h2>
 * A signal carries no learned failure mode today: its {@code verdict} node has no {@code grader}
 * row, so it is excluded from the {@code risk_stat} surface↔failure-mode fold. So the cold-start mapping
 * is an EXPLICIT {@code surfaces} list on the classifier definition's {@code config_json} (validated against
 * {@link TouchedSurface}). When neither a learned mapping (the nullable {@code failure_mode_id} seam,
 * reserved for a learned signal→failure-mode map) nor an explicit list exists, NO check is registered — the honest
 * {@code insufficient_history} discipline the rest of the wedge follows, never a fabricated surface.
 *
 * <h2>Fail-soft + off the hot path</h2>
 * {@link #registerForSignal} is invoked from the async sweep behind {@code evals.predeploy.enabled}
 * (default off); the caller wraps it fail-soft (logged, never failing the sweep or blocking the cursor),
 * matching {@code ClassifierWorker.emitVerdict}. It does no risk-model ranking — surface resolution is a cheap
 * config parse, so it stays safe even when many events fire in one sweep.
 */
@Service
public class PreDeployCheckService {

    private static final Logger log = LoggerFactory.getLogger(PreDeployCheckService.class);

    private final PreDeployCheckRepository checks;
    private final PreDeployProperties props;
    private final ObjectMapper mapper;

    public PreDeployCheckService(PreDeployCheckRepository checks, PreDeployProperties props, ObjectMapper mapper) {
        this.checks = checks;
        this.props = props;
        this.mapper = mapper;
    }

    /** Whether the loop is active (gates both the write trigger and the read-side union). */
    public boolean isEnabled() {
        return props.isEnabled();
    }

    /**
     * Register a routed pre-deploy check for a newly-discovered signal, one row per implicated surface,
     * idempotently. Returns the number of NEW rows written (0 when the signal maps to no surface, or all
     * surfaces were already registered). Never throws on a no-mapping signal — that is the honest no-op.
     */
    public int registerForSignal(ClassifierDiscovery discovery) {
        Set<String> surfaces = implicatedSurfaces(discovery);
        if (surfaces.isEmpty()) {
            return 0; // honest no-op: no learned/explicit mapping — never fabricate a surface
        }
        String now = Instant.now().toString();
        String intensity = intensityFor(discovery.severity());
        int registered = 0;
        for (String surface : surfaces) {
            boolean inserted = checks.insertClassifierIfAbsent(new PreDeployCheckRow(
                    Ids.ulid(),
                    discovery.projectId(),
                    discovery.classifierId(),
                    surface,
                    null, // failure_mode_id: the learned-mapping seam, null until a signal carries one
                    intensity,
                    PreDeployCheckRow.Status.ACTIVE,
                    now,
                    now));
            if (inserted) registered++;
        }
        if (registered > 0) {
            log.info(
                    Markers.OPS,
                    "predeploy registered project={} signal={} surfaces={}",
                    discovery.projectId(),
                    discovery.classifierKey(),
                    surfaces);
        }
        return registered;
    }

    /**
     * The implicated surfaces for a signal. Today: the explicit {@code surfaces} list on the classifier's
     * {@code config_json}, validated against {@link TouchedSurface} (unknown wire names are dropped, not
     * fabricated). This is the single resolution point a future learned signal→failure-mode→surface map
     * plugs into — it would augment (not replace) this set.
     */
    private Set<String> implicatedSurfaces(ClassifierDiscovery discovery) {
        Set<String> out = new LinkedHashSet<>();
        String configJson = discovery.configJson();
        if (configJson == null || configJson.isBlank()) return out;
        try {
            JsonNode node = mapper.readTree(configJson);
            JsonNode surfaces = node.get("surfaces");
            if (surfaces != null && surfaces.isArray()) {
                for (JsonNode s : surfaces) {
                    if (!s.isTextual()) continue;
                    TouchedSurface.parse(s.asText()).ifPresent(ts -> out.add(ts.wire()));
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "predeploy surface parse failed project={} signal={}: {}",
                    discovery.projectId(),
                    discovery.classifierKey(),
                    e.getMessage());
        }
        return out;
    }

    /** The DISTINCT active-check surfaces for the project — unioned into a future PR's forecast. */
    public List<String> activeSurfaces(String projectId) {
        return checks.activeSurfaces(projectId);
    }

    /** Every registered check for the project (the list read surface), newest first. */
    public List<PreDeployCheckView> list(String projectId) {
        return checks.listByProject(projectId).stream()
                .map(PreDeployCheckView::of)
                .toList();
    }

    /** Dismiss a noisy check WITHOUT disabling its signal (the tenant lifecycle the acceptance implies). */
    public void dismiss(String projectId, String id) {
        setStatus(projectId, id, PreDeployCheckRow.Status.DISMISSED);
    }

    /** Reinstate a previously-dismissed check. */
    public void reinstate(String projectId, String id) {
        setStatus(projectId, id, PreDeployCheckRow.Status.ACTIVE);
    }

    private void setStatus(String projectId, String id, String status) {
        if (checks.setStatus(projectId, id, status, Instant.now().toString()) == 0) {
            throw new EvalsException(PreDeployError.NOT_FOUND, id);
        }
    }

    /** Map a detection severity onto a coarse intensity tier; default medium for an absent severity. */
    private static String intensityFor(@Nullable String severity) {
        if (Severity.CRITICAL.equals(severity)) return PreDeployCheckRow.Intensity.HIGH;
        if (Severity.INFO.equals(severity)) return PreDeployCheckRow.Intensity.LOW;
        return PreDeployCheckRow.Intensity.MEDIUM;
    }

    /**
     * A signal discovery, projected to exactly what the registration needs. A narrow input record owned
     * by this package (not {@code ClassifierRow}) so the {@code ci} ← {@code classifier} dependency stays one-way
     * — the {@code ClassifierWorker} maps its row to this at the call site, mirroring how {@code RiskModelMiner}
     * accepts a {@code VersionSignal} rather than importing {@code ClassifierRow}.
     */
    public record ClassifierDiscovery(
            String projectId,
            String classifierId,
            String classifierKey,
            @Nullable String configJson,
            @Nullable String severity) {

        public static ClassifierDiscovery of(
                String projectId,
                String classifierId,
                String classifierKey,
                @Nullable String configJson,
                @Nullable String severity) {
            return new ClassifierDiscovery(projectId, classifierId, classifierKey, configJson, severity);
        }
    }
}
