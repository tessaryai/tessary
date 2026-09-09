// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves Grafana-style relative time tokens to absolute ISO-8601 instants.
 *
 * <p>Grammar: {@code now} or {@code now-<N><unit>} where unit is one of
 * {@code m} (minutes), {@code h} (hours), {@code d} (days), {@code w} (weeks).
 * Anything that isn't a {@code now…} token (an absolute ISO string, or null) is
 * returned unchanged, so callers can store either form in the same field.
 *
 * <p>Live-dataset filters store the token and resolve it against the current
 * wall-clock on every run, giving a window that rolls forward over time.
 */
public final class RelativeTime {

    private RelativeTime() {}

    private static final Pattern TOKEN = Pattern.compile("now(?:-(\\d+)([mhdw]))?");

    /** Resolve one bound. Relative tokens become absolute ISO; other values pass through. */
    public static @Nullable String resolve(@Nullable String value, Instant now) {
        if (value == null || value.isBlank()) return value;
        Matcher m = TOKEN.matcher(value.trim());
        if (!m.matches()) return value;
        if (m.group(1) == null) return now.toString();
        long n = Long.parseLong(m.group(1));
        // Instant.minus supports up to DAYS, not WEEKS, so express weeks as days.
        Instant resolved =
                switch (m.group(2)) {
                    case "m" -> now.minus(n, ChronoUnit.MINUTES);
                    case "h" -> now.minus(n, ChronoUnit.HOURS);
                    case "d" -> now.minus(n, ChronoUnit.DAYS);
                    case "w" -> now.minus(n * 7, ChronoUnit.DAYS);
                    default -> throw new IllegalStateException("unreachable unit: " + m.group(2));
                };
        return resolved.toString();
    }

    /** A copy of {@code filter} with both time bounds resolved against {@code now}. */
    public static ImportFilter resolveTimes(ImportFilter filter, Instant now) {
        String from = resolve(filter.fromTimestamp(), now);
        String to = resolve(filter.toTimestamp(), now);
        if (java.util.Objects.equals(from, filter.fromTimestamp())
                && java.util.Objects.equals(to, filter.toTimestamp())) {
            return filter;
        }
        return new ImportFilter(
                filter.lookbackHours(),
                filter.tag(),
                filter.projectId(),
                filter.name(),
                filter.model(),
                filter.limit(),
                from,
                to,
                filter.namePatterns(),
                filter.environment());
    }
}
