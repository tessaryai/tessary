// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import java.util.Set;

/**
 * What ingest needs about call sites, owned by ingest and implemented above it so the data plane never imports the
 * pipeline.
 */
public interface CallSiteRegistry {

    Set<String> callSiteIds(String projectId);

    /** Idempotent. */
    void ensureCallSite(String projectId, String callSiteId);
}
