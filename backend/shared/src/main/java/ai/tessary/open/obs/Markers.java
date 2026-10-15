// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * {@link #OPS} marks events safe to leave the perimeter (bounded cardinality, no user-supplied fields). The OTEL
 * appender ships WARN+ or OPS events; everything else stays local.
 */
public final class Markers {

    public static final Marker OPS = MarkerFactory.getMarker("OPS");

    private Markers() {}
}
