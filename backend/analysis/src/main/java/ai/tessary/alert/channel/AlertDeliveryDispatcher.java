// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertChannelRepository;
import ai.tessary.alert.AlertChannelRow;
import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.ChannelFactory;
import ai.tessary.alert.DeliveryAttemptRepository;
import ai.tessary.crypto.SecretBox;
import ai.tessary.open.obs.Markers;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Channel fan-out for a fired alert, split from {@code AlertDeliveryListener} so {@code @Async} goes through a proxy. A
 * withheld transport is skipped here too, so a flag going off reaches channels created before it. Each channel claims
 * its {@code (alert_event, channel)} slot first and is isolated from the others; there is no retry, and the attempt row
 * is the visibility surface.
 */
@Component
public class AlertDeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDeliveryDispatcher.class);

    private final AlertChannelRepository destinations;
    private final DeliveryAttemptRepository attempts;
    private final ChannelFactory factory;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;
    private final CapabilityService capabilities;
    private final ProjectRepository projects;

    public AlertDeliveryDispatcher(
            AlertChannelRepository destinations,
            DeliveryAttemptRepository attempts,
            ChannelFactory factory,
            SecretBox secretBox,
            ObjectMapper mapper,
            CapabilityService capabilities,
            ProjectRepository projects) {
        this.destinations = destinations;
        this.attempts = attempts;
        this.factory = factory;
        this.secretBox = secretBox;
        this.mapper = mapper;
        this.capabilities = capabilities;
        this.projects = projects;
    }

    /** Runs on {@code alertDeliveryExecutor} so blocking POSTs never stall the AlertWorker thread. */
    @Async("alertDeliveryExecutor")
    public void deliverAll(AlertEventRow row) {
        List<AlertChannelRow> enabled = destinations.listEnabledByProject(row.projectId());
        if (enabled.isEmpty()) return;
        boolean slackAllowed = allows(row.projectId(), Capability.SLACK);
        for (AlertChannelRow channel : enabled) {
            if (!slackAllowed && AlertChannelKind.SLACK.wire().equals(channel.kind())) {
                // No attempt row: a withheld transport is not a failed send.
                log.info(
                        Markers.OPS,
                        "alert delivery skipped — slack withheld projectId={} channel={}",
                        row.projectId(),
                        channel.id());
                continue;
            }
            deliverOne(row, channel);
        }
    }

    /** Fail-closed, as in {@code AlertWorker}. */
    private boolean allows(String projectId, Capability capability) {
        return projects.findById(projectId)
                .map(p -> capabilities.isEnabled(p.orgId(), capability))
                .orElse(false);
    }

    private void deliverOne(AlertEventRow event, AlertChannelRow channel) {
        String attemptId = Ids.ulid();
        // A re-fired event finds the slot taken and skips.
        if (!attempts.claim(attemptId, event.id(), channel.id(), event.projectId())) {
            return;
        }
        try {
            JsonNode config = openConfig(channel);
            DeliveryResult result = factory.forKind(channel.kindEnum()).deliver(event, config);
            if (result.ok()) {
                attempts.markDelivered(attemptId, result.httpStatus());
            } else {
                attempts.markFailed(attemptId, result.httpStatus(), result.error());
                log.info(
                        Markers.OPS,
                        "alert delivery failed projectId={} channel={} kind={} http={} reason={}",
                        event.projectId(),
                        channel.id(),
                        channel.kind(),
                        result.httpStatus(),
                        result.error());
            }
        } catch (RuntimeException e) {
            // Log with NO throwable: an open-config failure's cause is a Jackson error embedding the decrypted config
            // (secrets), and WARN+ egresses to Loki. The stored reason is only our own top-level message, never the
            // cause chain.
            attempts.markFailed(attemptId, null, e.getMessage() == null ? "delivery error" : e.getMessage());
            log.warn(
                    Markers.OPS,
                    "alert delivery error projectId={} channel={} kind={}",
                    event.projectId(),
                    channel.id(),
                    channel.kind());
        }
    }

    private JsonNode openConfig(AlertChannelRow channel) {
        try {
            String json = secretBox.open(channel.configEnc());
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("could not open channel config", e);
        }
    }
}
