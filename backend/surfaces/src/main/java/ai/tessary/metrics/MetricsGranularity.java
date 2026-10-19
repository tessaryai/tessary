// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public enum MetricsGranularity {
    AUTO("auto"),
    HOUR("hour"),
    DAY("day"),
    WEEK("week");

    private static final Duration AUTO_HOUR_MAX = Duration.ofHours(72);

    private final String wire;

    MetricsGranularity(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static @Nullable MetricsGranularity fromWire(@Nullable String wire) {
        if (wire == null) return null;
        for (MetricsGranularity g : values()) {
            if (g.wire.equals(wire)) return g;
        }
        throw new TessaryException(MetricsError.UNKNOWN_GRANULARITY, wire);
    }

    public static MetricsGranularity autoFor(Duration window) {
        return window.compareTo(AUTO_HOUR_MAX) <= 0 ? HOUR : DAY;
    }

    /** Postgres date_trunc(unit, at, 'UTC'): a week starts on Monday 00:00 UTC. */
    public Instant truncate(Instant at) {
        return switch (this) {
            case AUTO -> throw new IllegalArgumentException("auto is resolved before bucketing");
            case HOUR -> at.truncatedTo(ChronoUnit.HOURS);
            case DAY -> at.truncatedTo(ChronoUnit.DAYS);
            case WEEK ->
                at.atOffset(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.DAYS)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toInstant();
        };
    }

    /** Every bucket start from the one holding {@code from} to the one holding the window's last microsecond. */
    public List<Instant> buckets(Instant from, Instant to) {
        Instant last = truncate(to.minus(1, ChronoUnit.MICROS));
        Duration step =
                this == WEEK ? Duration.ofDays(7) : Duration.of(1, this == HOUR ? ChronoUnit.HOURS : ChronoUnit.DAYS);
        List<Instant> out = new ArrayList<>();
        for (Instant b = truncate(from); !b.isAfter(last); b = b.plus(step)) {
            out.add(b);
        }
        return out;
    }
}
