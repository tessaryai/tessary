// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The classifier's own evidence payload, with the substrate ids stripped out, for handing to an agent
 * that has no dedicated dossier assembler — see {@link
 * ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler} for the classifiers that do.
 *
 * <h2>The evidence-bias contract (revised by D, #994)</h2>
 *
 * <p>The dossier's job is to state the CLAIM — what was measured, over what, against what — and to be
 * honest about how the agent got from that claim to the instances it cites. The contract used to be
 * "no ids, the agent pages MCP for evidence, nothing here names an instance"; that rule is now:
 * <b>complete enumeration when the evidence set is small, or a declared deterministic selection rule
 * plus population counts when it is not — never an undeclared sample.</b> An undeclared sample dressed
 * as a full picture is what actually broke: two fields ({@code failing_traces},
 * {@code changepoint_trace_id}) carried a handful of trace ids picked newest-first, with no statement
 * that they were a sample at all, sitting beside a fully enumerated evidence set the agent was told to
 * page instead. An agent handed five ids and a suggestion to read them mostly reads those five — so the
 * claim got audited against whichever instances the detector happened to surface. Naming ids is not the
 * defect; an undeclared SELECTION masquerading as the whole story is. {@link
 * ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler} is where the declared form of
 * that lives for the classifiers it covers, keyed off {@code TRACE_SPAN_CAP}'s ≤200-row line for what
 * counts as "small enough to enumerate whole".
 *
 * <p>This class stays the fallback for every OTHER classifier: still strips the two undeclared-sample
 * fields (still true that a bare id list with no stated rule is worse than the enumerated evidence
 * table the agent already has MCP access to), but is no longer claimed as the general answer to the
 * bias question — a classifier that wants the fuller contract gets a dedicated assembler instead.
 *
 * <h2>Stripped here, kept on the wire</h2>
 *
 * <p>The fields stay in the stored payload and on the API, because the finding page renders
 * {@code failing_traces} as links a PERSON follows. A human skimming a few instances is a reading aid;
 * an agent doing it is a biased sample presented as evidence. The difference is the reader, so the
 * filter belongs at the sandbox boundary rather than at the write.
 */
public final class DossierPayload {

    private DossierPayload() {}

    /** Payload keys that name substrate rows. Additive: a new one is a new leak, so add it here. */
    private static final List<String> TRACE_ID_FIELDS = List.of("failing_traces", "changepoint_trace_id");

    /**
     * The payload without its trace-id fields, or null when there was no payload to begin with. An
     * unparseable blob comes back unchanged rather than being dropped: the numbers are the dossier's
     * whole point, and losing them to be tidy about ids would be the worse trade.
     */
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
