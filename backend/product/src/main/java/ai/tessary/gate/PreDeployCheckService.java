// SPDX-License-Identifier: Apache-2.0
package ai.tessary.gate;

import ai.tessary.config.PreDeployProperties;
import ai.tessary.gate.PreDeployCheckDtos.PreDeployCheckView;
import ai.tessary.model.Severity;
import ai.tessary.model.TouchedSurface;
import ai.tessary.open.errors.PreDeployError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.Ids;
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
 * Registers surface-scoped {@link PreDeployCheckRow}s when a new automatic detection is discovered. Surfaces come only
 * from an explicit {@code surfaces} list on the classifier config; with none, no check is registered rather than a
 * fabricated surface. Gated by {@code tessary.predeploy.enabled}; the caller wraps it fail-soft.
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

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Returns the number of NEW rows written; 0 for a signal with no surface mapping. */
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

    /** Unknown wire names are dropped. The single point a learned mapping would augment. */
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

    public List<PreDeployCheckView> list(String projectId) {
        return checks.listByProject(projectId).stream()
                .map(PreDeployCheckView::of)
                .toList();
    }

    /** Without disabling its signal. */
    public void dismiss(String projectId, String id) {
        setStatus(projectId, id, PreDeployCheckRow.Status.DISMISSED);
    }

    public void reinstate(String projectId, String id) {
        setStatus(projectId, id, PreDeployCheckRow.Status.ACTIVE);
    }

    private void setStatus(String projectId, String id, String status) {
        if (checks.setStatus(projectId, id, status, Instant.now().toString()) == 0) {
            throw new TessaryException(PreDeployError.NOT_FOUND, id);
        }
    }

    /** Defaults to medium for an absent severity. */
    private static String intensityFor(@Nullable String severity) {
        if (Severity.CRITICAL.equals(severity)) return PreDeployCheckRow.Intensity.HIGH;
        if (Severity.INFO.equals(severity)) return PreDeployCheckRow.Intensity.LOW;
        return PreDeployCheckRow.Intensity.MEDIUM;
    }

    /**
     * A narrow input record (not {@code ClassifierRow}) so the {@code ci} to {@code classifier} dependency stays
     * one-way.
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
