// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.alert.AlertChannelDtos.UpsertChannelRequest;
import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CRUD + credential sealing for per-project alert channels. Mirrors
 * {@code git/GitIntegrationService}: the channel's config (target URL + credentials) is sealed with
 * {@link SecretBox} (AES-256-GCM, {@code tessary.secret-key}) before it touches the DB and is never
 * returned over the API. The fan-out path ({@code AlertDeliveryDispatcher}) opens the sealed config just
 * before delivery.
 */
@Service
public class AlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelService.class);

    private final AlertChannelRepository repo;
    private final DeliveryAttemptRepository attempts;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;

    public AlertChannelService(
            AlertChannelRepository repo, DeliveryAttemptRepository attempts, SecretBox secretBox, ObjectMapper mapper) {
        this.repo = repo;
        this.attempts = attempts;
        this.secretBox = secretBox;
        this.mapper = mapper;
    }

    public List<AlertChannelRow> list(String projectId) {
        return repo.listByProject(projectId);
    }

    public AlertChannelRow require(String projectId, String id) {
        return repo.find(projectId, id).orElseThrow(() -> new TessaryException(AlertError.CHANNEL_NOT_FOUND, id));
    }

    public AlertChannelRow create(String projectId, UpsertChannelRequest req) {
        AlertChannelKind kind = parseKind(req.kind());
        String configEnc = sealConfig(kind, req.config());
        Boolean requested = req.enabled();
        boolean enabled = requested == null || requested;
        String now = Instant.now().toString();
        AlertChannelRow row =
                new AlertChannelRow(Ids.ulid(), projectId, kind.wire(), req.name(), enabled, configEnc, "{}", now, now);
        repo.insert(row);
        log.info(Markers.OPS, "alert channel created projectId={} kind={} id={}", projectId, kind.wire(), row.id());
        return row;
    }

    public AlertChannelRow update(String projectId, String id, UpsertChannelRequest req) {
        AlertChannelRow existing = require(projectId, id);
        AlertChannelKind kind = parseKind(req.kind());
        // Re-seal config only when a new config object is supplied; otherwise keep the stored secret.
        var cfg = req.config();
        String configEnc = cfg != null && !cfg.isEmpty() ? sealConfig(kind, cfg) : existing.configEnc();
        Boolean requested = req.enabled();
        boolean enabled = requested == null || requested;
        AlertChannelRow row = new AlertChannelRow(
                existing.id(),
                projectId,
                kind.wire(),
                req.name(),
                enabled,
                configEnc,
                existing.attributes(),
                existing.createdAt(),
                Instant.now().toString());
        repo.update(row);
        log.info(Markers.OPS, "alert channel updated projectId={} id={}", projectId, id);
        return row;
    }

    public boolean delete(String projectId, String id) {
        boolean deleted = repo.delete(projectId, id);
        if (deleted) log.info(Markers.OPS, "alert channel removed projectId={} id={}", projectId, id);
        return deleted;
    }

    public List<DeliveryAttemptRow> recentDeliveries(String projectId, int limit) {
        return attempts.listByProject(projectId, limit);
    }

    private AlertChannelKind parseKind(String wire) {
        try {
            return AlertChannelKind.fromWire(wire);
        } catch (IllegalArgumentException e) {
            throw new TessaryException(AlertError.UNSUPPORTED_CHANNEL, e, wire);
        }
    }

    private String sealConfig(AlertChannelKind kind, @Nullable Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, "config is required");
        }
        if (!secretBox.isConfigured()) {
            throw new TessaryException(AlertError.MISSING_SECRET_KEY, kind.wire());
        }
        try {
            return secretBox.seal(mapper.writeValueAsString(config));
        } catch (TessaryException e) {
            throw e;
        } catch (Exception e) {
            throw new TessaryException(AlertError.INVALID_CHANNEL_CONFIG, e, "could not seal config");
        }
    }
}
