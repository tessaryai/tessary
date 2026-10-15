// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

/**
 * A fact about a call site's code, captured from the repo during synthesis rather than read off telemetry. Facts arrive
 * after the traffic they describe, and a detector gated on a missing fact is quiet, not clean, while the cursor
 * advances anyway; {@link CallSiteFactChangedEvent} reopens that history.
 */
public enum CallSiteFact {

    /** {@code call_site.output_schema}, read by Malformed Output. */
    OUTPUT_SCHEMA,

    /**
     * {@code call_site.shape}, read by Groundedness, which only scores shapes whose input carries verifiable source
     * content.
     */
    SHAPE
}
