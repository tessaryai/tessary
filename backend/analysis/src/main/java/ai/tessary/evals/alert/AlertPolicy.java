// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

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
 * <b>When a partner is willing to hear from us</b> — the cadence and quiet hours of launch requirement
 * I3, and the only two dials on a {@link AlertRuleRow.RuleType#CASE_OPENED} rule.
 *
 * <p>Stored in {@code alert_rule.attributes} rather than in columns of its own. The jsonb column exists
 * for exactly this: policy that belongs to one rule type, is read only by the worker that evaluates it,
 * and is not queried across rules. Three nullable columns on a table shared by five rule types would have
 * been three columns meaningless to four of them.
 *
 * <h2>Quiet hours defer; they never drop</h2>
 *
 * <p>This is the design decision worth stating, because the other one is easy and wrong. Suppressing a
 * page during quiet hours and moving on would mean a regression that opened at 2am is never mentioned
 * again — the case exists in the product, and segment I's goal is that <em>nobody has to be looking at
 * the app</em> to find out. Silently dropping a third of the day's notifications makes that goal false
 * for a third of the day, and does it in the least visible way possible.
 *
 * <p>So a quiet tick delivers nothing AND does not advance the rule's anchor. Everything that opened
 * while the window was shut is still "since the anchor" when it reopens, and goes out then. The cost is
 * bounded: a quiet window ends every day, and the message says when the case actually opened, so nobody
 * reads a 6-hour-old regression as a new one.
 *
 * @param cadenceSeconds the MINIMUM spacing between deliveries. Zero — the default — means every tick,
 *     which is as immediate as the heartbeat is. Set it and the cases that open in between are batched
 *     into the next delivery rather than dropped, by the same anchor mechanism as quiet hours.
 * @param quietFrom start of the quiet window in {@link #zone}'s local time, or null for none.
 * @param quietTo end of the quiet window. A window that wraps midnight ({@code 22:00} → {@code 08:00}) is
 *     the normal case rather than the exceptional one, and is what {@link #isQuiet} is written around.
 * @param zone the IANA zone the two local times are read in. Required whenever a window is set: "22:00"
 *     with no zone is not a time, and defaulting it to the server's would put a partner's quiet hours
 *     wherever we happen to deploy.
 */
public record AlertPolicy(
        long cadenceSeconds,
        @Nullable LocalTime quietFrom,
        @Nullable LocalTime quietTo,
        ZoneId zone) {

    /** Every tick, no quiet window — what a rule seeded with no policy does. */
    public static final AlertPolicy IMMEDIATE = new AlertPolicy(0, null, null, ZoneId.of("UTC"));

    private static final String CADENCE = "cadence_seconds";
    private static final String QUIET_FROM = "quiet_from";
    private static final String QUIET_TO = "quiet_to";
    private static final String ZONE = "quiet_zone";

    /** A day, so a cadence cannot be set to a value that makes the rule look broken rather than quiet. */
    private static final long MAX_CADENCE_SECONDS = 86_400;

    /**
     * Read a policy out of a rule's {@code attributes}. Every unreadable or partial input degrades to
     * {@link #IMMEDIATE} rather than throwing, and that direction is deliberate: this is consulted on a
     * heartbeat, and a malformed blob must not be able to stop a project's pages going out. Failing open
     * to "notify" is the safe failure for an alerting policy; failing open to "stay quiet" is not.
     */
    public static AlertPolicy of(ObjectMapper mapper, @Nullable String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return IMMEDIATE;
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
     * Build from the raw strings an API request carries, leniently — the one parser both the stored blob
     * and the wire DTO go through, so a policy cannot mean one thing when saved and another when read
     * back.
     *
     * <p>Every unreadable part degrades to its permissive default rather than being rejected. That
     * direction is the same one {@link #of(ObjectMapper, String)} takes and is worth repeating: the
     * failure mode of a misconfigured notification policy must be a partner who is notified, not one who
     * is silently not.
     */
    public static AlertPolicy of(
            long cadenceSeconds, @Nullable String quietFrom, @Nullable String quietTo, @Nullable String zoneId) {
        long cadence = Math.clamp(cadenceSeconds, 0, MAX_CADENCE_SECONDS);
        LocalTime from = time(quietFrom);
        LocalTime to = time(quietTo);
        ZoneId zone = zone(zoneId);
        // Half a window is not a window. Both ends or neither — a rule with only a start would be quiet
        // forever, which is the one outcome a partner would not detect until they missed a page.
        return from == null || to == null
                ? new AlertPolicy(cadence, null, null, zone)
                : new AlertPolicy(cadence, from, to, zone);
    }

    /** Render back into the {@code attributes} blob, preserving nothing else — this owns the object. */
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

    /**
     * Whether {@code now} falls inside the quiet window.
     *
     * <p>A window whose ends are equal is treated as no window at all rather than as 24 hours of silence.
     * Someone setting 09:00 → 09:00 meant "none"; reading it as "always" would mute a project
     * permanently, and they would find out by not being told about an outage.
     */
    public boolean isQuiet(Instant now) {
        LocalTime from = quietFrom;
        LocalTime to = quietTo;
        if (from == null || to == null || from.equals(to)) return false;
        LocalTime local = LocalTime.from(now.atZone(zone));
        return from.isBefore(to)
                ? !local.isBefore(from) && local.isBefore(to)
                : !local.isBefore(from) || local.isBefore(to); // wraps midnight
    }

    /** Whether enough time has passed since the last delivery for the next one to go out. */
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
