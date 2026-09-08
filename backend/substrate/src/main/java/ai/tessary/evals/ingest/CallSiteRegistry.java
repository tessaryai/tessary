// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import java.util.Set;

/**
 * The two things ingest needs to know about call sites, owned by ingest and implemented above it.
 *
 * <p>Ingest tags a span with the call site it belongs to, so it must ask which call sites exist and
 * register one it has not seen. Reaching for {@code PipelineService} to do that inverted the layering:
 * the substrate is written on every trace, the pipeline is a product artefact fitted on top of it, and
 * an import from the first to the second makes the data plane depend on the product model.
 *
 * <p>Declaring the seam here rather than there is what keeps it that way. A future consumer that wants
 * to resolve call sites differently — a cache, a different source of truth — implements this; nothing
 * about ingest changes, and nothing new appears in its imports.
 */
public interface CallSiteRegistry {

    /** Every call-site id known for the project. Used to decide whether a span's tag is already known. */
    Set<String> callSiteIds(String projectId);

    /** Register a call site observed on the wire that the project does not yet have. Idempotent. */
    void ensureCallSite(String projectId, String callSiteId);
}
