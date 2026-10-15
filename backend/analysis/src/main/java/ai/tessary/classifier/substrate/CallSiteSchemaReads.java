// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.Map;
import java.util.Set;

/**
 * Call sites' declared {@code call_site.output_schema}, for Malformed Output. A call site without one is absent and
 * simply not validated.
 */
public interface CallSiteSchemaReads {

    Map<String, String> callSiteOutputSchemas(String projectId, Set<String> callSiteIds);
}
