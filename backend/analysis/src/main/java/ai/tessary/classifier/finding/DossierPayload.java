// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The evidence payload with substrate ids stripped, for agents without a dedicated assembler (see
 * {@link ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler}). An undeclared sample of trace ids biases
 * an agent toward those instances; the finding page keeps them as links because a person skimming a few is a reading
 * aid. So the filter belongs at the sandbox boundary, not at the write.
 */
public final class DossierPayload {

    private DossierPayload() {}

    /** Additive: a new one is a new leak, so add it here. */
    private static final List<String> TRACE_ID_FIELDS = List.of("failing_traces", "changepoint_trace_id");

    /** Null when there was no payload. An unparseable blob comes back unchanged: the numbers are the point. */
    public static @Nullable String forAgent(ObjectMapper mapper, @Nullable String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(payloadJson);
            if (!(root instanceof ObjectNode obj)) return payloadJson;
            for (String field : TRACE_ID_FIELDS) obj.remove(field);
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            return payloadJson;
        }
    }
}
