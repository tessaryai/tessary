// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.Map;
import java.util.Set;

/**
 * Call sites' declared {@code call_site.shape}, for Groundedness. A call site with no recognized shape is absent and so
 * not gated in, as with {@link CallSiteSchemaReads}.
 */
public interface CallSiteShapeReads {

    Map<String, String> callSiteShapes(String projectId, Set<String> callSiteIds);
}
