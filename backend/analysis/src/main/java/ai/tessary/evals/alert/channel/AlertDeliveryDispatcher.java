// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.alert.AlertChannelKind;
import ai.tessary.evals.alert.AlertChannelRepository;
import ai.tessary.evals.alert.AlertChannelRow;
import ai.tessary.evals.alert.AlertEventRow;
import ai.tessary.evals.alert.ChannelFactory;
import ai.tessary.evals.alert.DeliveryAttemptRepository;
import ai.tessary.evals.crypto.SecretBox;
import ai.tessary.evals.open.obs.Markers;
import ai.tessary.evals.plan.Capability;
import ai.tessary.evals.plan.CapabilityService;
import ai.tessary.evals.tenant.Ids;
import ai.tessary.evals.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the actual channel fan-out for a fired alert, off the publisher thread. Split out of
 * {@code AlertDeliveryListener} so the {@code @Async} hand-off goes through a Spring proxy (a
 * self-invocation inside the listener would bypass the proxy and still run inline) — the same
 * post-commit-listener → async-executor idiom used elsewhere.
 *
 * <p><b>A withheld transport is skipped here, not only at configuration time.</b> Slack is a capability of
 * its own and is off for launch; refusing new Slack channels at the API would leave every channel created
 * before the flag flipped still delivering. Same reasoning segment D applies to a withdrawn classifier's
 * output — a flag going off has to reach what already exists or it is a half-measure. Resolved once per
 * fan-out rather than per channel: one capability read, however many channels a project has.
 *
 * <p>Per channel the flow is: claim the {@code (alert_event, channel)} slot in the
 * delivery-attempt log (idempotent insert — a re-fire never double-sends), open the SecretBox-sealed
 * config, dispatch through {@link ChannelFactory} to the right SPI, then record the outcome. Each
 * channel is isolated — a failure (or a thrown bad-config) on one never aborts the fan-out to the
 * others. Delivery is best-effort and synchronous within the async task: there is no retry/DLQ; the
 * delivery-attempt row IS the visibility surface.
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

    /**
     * Fan a fired alert out to every enabled channel for its project. Runs on the bounded
     * virtual-thread {@code alertDeliveryExecutor} pool (see {@code config/AsyncConfig}) so the N
     * blocking HTTP POSTs (each up to the 30s {@link ChannelHttp} timeout) never stall the
     * publishing {@code @Scheduled} AlertWorker thread.
     */
    @Async("alertDeliveryExecutor")
    public void deliverAll(AlertEventRow row) {
        List<AlertChannelRow> enabled = destinations.listEnabledByProject(row.projectId());
        if (enabled.isEmpty()) return;
        boolean slackAllowed = allows(row.projectId(), Capability.SLACK);
        for (AlertChannelRow channel : enabled) {
            if (!slackAllowed && AlertChannelKind.SLACK.wire().equals(channel.kind())) {
                // No delivery-attempt row: nothing was attempted. A withheld transport is not a failed
                // send, and recording it as one would put a permanent red line in the delivery log for a
                // channel the org simply may not use.
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

    /** Unknown project → not allowed, matching {@code AlertWorker}'s fail-closed entitlement read. */
    private boolean allows(String projectId, Capability capability) {
        return projects.findById(projectId)
                .map(p -> capabilities.isEnabled(p.orgId(), capability))
                .orElse(false);
    }

    private void deliverOne(AlertEventRow event, AlertChannelRow channel) {
        String attemptId = Ids.ulid();
        // Reserve the slot first — a re-fired event (or fallback double-dispatch) finds it taken and skips.
        if (!attempts.claim(attemptId, event.id(), channel.id(), event.projectId())) {
            return;
        }
        try {
            JsonNode config = openConfig(channel);
            DeliveryResult result = factory.forKind(channel.kindEnum()).deliver(event, config);
            if (result.ok()) {
                attempts.markDelivered(attemptId, result.httpStatus());
            } else {
                attempts.markFailed(
                        attemptId, result.httpStatus(), result.error() == null ? "delivery failed" : result.error());
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
            // A bad config (missing url/credential) or any unexpected error: record and continue the fan-out.
            // NB: log categorically with NO throwable attached. The cause of an open-config failure is a
            // Jackson parse failure on the *decrypted* config, whose message embeds the plaintext config
            // JSON (signing secret / API key / routing key); attaching `e` would ship those bytes to Loki
            // (the OTEL appender captures the throwable's message + stack on WARN+). See AGENTS.md
            // "WARN+ egress to Loki is a data-egress surface". The stored reason uses only the top-level
            // `getMessage()` (always our own categorical wrapper text — `openConfig` re-wraps the Jackson
            // cause as "could not open channel config", connectors throw EvalsException with static
            // messages), never the cause chain, so the decrypted config never reaches the DB row either.
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
