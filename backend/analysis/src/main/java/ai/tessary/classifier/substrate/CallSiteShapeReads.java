// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.Map;
import java.util.Set;

/**
 * Read seam for the Groundedness built-in: a batch's call sites' declared {@code shape}
 * ({@code call_site.shape}, populated during synthesis — {@code summarise}/{@code rag}/
 * {@code extract}/{@code draft}/{@code agent_step}/…). Only call sites classified with a shape
 * appear in the result — a call site with none (or an unrecognized/open-generation shape) is
 * simply not gated in, mirroring {@link CallSiteSchemaReads}.
 */
public interface CallSiteShapeReads {

    /** {@code call_site_id -> shape} for the ids that have one. */
    Map<String, String> callSiteShapes(String projectId, Set<String> callSiteIds);
}
