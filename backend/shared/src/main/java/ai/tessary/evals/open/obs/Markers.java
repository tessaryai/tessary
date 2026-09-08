// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.obs;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * SLF4J markers used to route logs.
 *
 * <p>{@link #OPS} tags safe-to-egress operational events (run lifecycle, counts,
 * durations — bounded-cardinality, no user-supplied or sensitive fields). The
 * {@code postgres}-profile OTEL appender ships WARN+ <em>or</em> OPS-marked
 * events to Grafana Cloud; everything else stays in local JSON on the box. Only
 * mark events whose every field is safe to leave the perimeter.
 */
public final class Markers {

    public static final Marker OPS = MarkerFactory.getMarker("OPS");

    private Markers() {}
}
