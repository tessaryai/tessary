// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * Quiet hours defer, never drop: a quiet tick delivers nothing and does not advance the rule's anchor, so everything
 * that opened goes out when the window reopens. @param cadenceSeconds minimum spacing; cases opening in between are
 * batched, not dropped @param quietTo a window wrapping midnight is the normal case @param zone required with a window;
 * defaulting to the server's zone would move quiet hours with the deployment
 */
public record AlertPolicy(
        long cadenceSeconds,
        @Nullable LocalTime quietFrom,
        @Nullable LocalTime quietTo,
        ZoneId zone) {

    public static final AlertPolicy IMMEDIATE = new AlertPolicy(0, null, null, ZoneId.of("UTC"));

    private static final String CADENCE = "cadence_seconds";
    private static final String QUIET_FROM = "quiet_from";
    private static final String QUIET_TO = "quiet_to";
    private static final String ZONE = "quiet_zone";

    /** A day, so a cadence cannot make the rule look broken rather than quiet. */
    private static final long MAX_CADENCE_SECONDS = 86_400;

    /**
     * An unreadable blob degrades to {@link #IMMEDIATE}: failing open to "notify" is the safe failure for an alerting
     * policy.
     */
    public static AlertPolicy of(ObjectMapper mapper, String attributesJson) {
        try {
            JsonNode root = mapper.readTree(attributesJson);
            return of(
                    root.path(CADENCE).asLong(0),
                    root.path(QUIET_FROM).asText(null),
                    root.path(QUIET_TO).asText(null),
                    root.path(ZONE).asText(null));
        } catch (Exception e) {
            return IMMEDIATE;
        }
    }

    /**
     * The one parser for both the stored blob and the wire DTO. Unreadable parts degrade to permissive defaults, as
     * above.
     */
    public static AlertPolicy of(
            long cadenceSeconds, @Nullable String quietFrom, @Nullable String quietTo, @Nullable String zoneId) {
        long cadence = Math.clamp(cadenceSeconds, 0, MAX_CADENCE_SECONDS);
        LocalTime from = time(quietFrom);
        LocalTime to = time(quietTo);
        ZoneId zone = zone(zoneId);
        // Both ends or neither: a start alone would be quiet forever.
        return from == null || to == null
                ? new AlertPolicy(cadence, null, null, zone)
                : new AlertPolicy(cadence, from, to, zone);
    }

    public String toJson(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put(CADENCE, cadenceSeconds);
        root.put(ZONE, zone.getId());
        if (quietFrom != null && quietTo != null) {
            root.put(QUIET_FROM, quietFrom.toString());
            root.put(QUIET_TO, quietTo.toString());
        }
        return root.toString();
    }

    /** Equal ends mean no window, not 24 hours of silence. */
    public boolean isQuiet(Instant now) {
        LocalTime from = quietFrom;
        LocalTime to = quietTo;
        if (from == null || to == null || from.equals(to)) return false;
        LocalTime local = LocalTime.from(now.atZone(zone));
        return from.isBefore(to)
                ? !local.isBefore(from) && local.isBefore(to)
                : !local.isBefore(from) || local.isBefore(to); // wraps midnight
    }

    public boolean cadenceElapsed(Instant lastDeliveredAt, Instant now) {
        return cadenceSeconds <= 0 || !now.isBefore(lastDeliveredAt.plus(Duration.ofSeconds(cadenceSeconds)));
    }

    private static @Nullable LocalTime time(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ZoneId zone(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }
}
