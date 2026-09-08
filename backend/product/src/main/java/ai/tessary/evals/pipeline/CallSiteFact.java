// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.pipeline;

/**
 * A fact about a call site's CODE, captured from the repo during agentic synthesis rather than read
 * off telemetry — the two columns a built-in classifier can gate itself on.
 *
 * <p>These arrive on their own clock, and later than the traffic they describe: the plugin refuses to
 * assess a repo until the platform has ingested correctly-tagged traces, so the earliest observations
 * of any project are necessarily swept before any of these facts exist. A detector gated on a missing
 * fact is <b>quiet, not clean</b> ({@code Detection.none()}), and the sweep cursor advances past that
 * history regardless — which would strand it permanently. {@link CallSiteFactChangedEvent} is the
 * seam that closes the gap: a fact landing (or changing) invalidates what the detectors reading it
 * have already swept.
 */
public enum CallSiteFact {

    /**
     * {@code call_site.output_schema} — the declared structured-output JSON Schema. Read by the
     * Malformed Output built-in.
     */
    OUTPUT_SCHEMA,

    /**
     * {@code call_site.shape} — {@code summarize}/{@code extract}/{@code rag_answer}/{@code draft}/…
     * Read by the Groundedness built-in, which only scores shapes whose input carries verifiable
     * source content.
     */
    SHAPE
}
