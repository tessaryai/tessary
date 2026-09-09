// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.substrate;

import java.util.Map;
import java.util.Set;

/**
 * Read seam for the Malformed Output built-in: the declared structured-output JSON Schemas of a
 * set of call sites ({@code call_site.output_schema}, captured during agentic synthesis). Only
 * call sites that declare a schema appear in the result — a call site without one is simply not
 * validated.
 */
public interface CallSiteSchemaReads {

    /** {@code call_site_id → output_schema JSON} for the ids that have one. */
    Map<String, String> callSiteOutputSchemas(String projectId, Set<String> callSiteIds);
}
