// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import ai.tessary.classifier.detector.MalformedOutputDetector;
import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.malformed.MalformedOutputEvidence.MalformedDetail;
import ai.tessary.classifier.substrate.CallSiteSchemaReads;
import ai.tessary.redaction.CredentialMasking;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * "How outputs broke": the declared schema, annotated with how many outputs have failed each field
 * since the finding's onset, and the one failing output a reader is looking at. The only DB-backed
 * half of {@link MalformedOutputEvidence} — the schema and the failure counts have no home in the
 * finding's own payload, unlike a tool-error or metric-drift blob.
 */
@Service
public class MalformedOutputDetailService {

    /** A document past this many characters is truncated — a reader is judging a shape, not auditing bytes. */
    private static final int MAX_DOCUMENT_CHARS = 20_000;

    private final CallSiteSchemaReads schemas;
    private final MalformedOutputRateRepository rates;
    private final FindingEvidenceRepository evidence;
    private final SpanPayloadRepository payloads;
    private final ObjectMapper mapper;

    public MalformedOutputDetailService(
            CallSiteSchemaReads schemas,
            MalformedOutputRateRepository rates,
            FindingEvidenceRepository evidence,
            SpanPayloadRepository payloads,
            ObjectMapper mapper) {
        this.schemas = schemas;
        this.rates = rates;
        this.evidence = evidence;
        this.payloads = payloads;
        this.mapper = mapper;
    }

    /** Null for any finding that is not a live {@code malformed_rate} cause. */
    public @Nullable MalformedDetail detail(FindingRow finding) {
        String callSiteId = finding.callSiteId();
        if (!FindingRow.Cause.MALFORMED_RATE.equals(finding.causeKind()) || callSiteId == null) return null;

        Map<String, Long> counts =
                rates.fieldFailureCounts(finding.projectId(), finding.subjectId(), callSiteId, finding.onsetAt());
        List<MalformedOutputEvidence.SchemaFieldView> fields = schemaFields(finding.projectId(), callSiteId, counts);
        return new MalformedDetail(
                MalformedOutputEvidence.rateDetail(finding, witnessTraceIds(finding)),
                fields,
                counts.getOrDefault(MalformedOutputRateRepository.FIELD_NOT_JSON, 0L),
                counts.getOrDefault(MalformedOutputRateRepository.FIELD_OTHER, 0L));
    }

    /**
     * One field's failing outputs since onset, newest first: what the finding page renders on the right
     * once a reader selects a row of the schema tree.
     */
    public MalformedOutputEvidence.FailingOutputPage failingOutputs(
            FindingRow finding, String field, int limit, @Nullable String cursor) {
        String callSiteId = finding.callSiteId();
        if (!FindingRow.Cause.MALFORMED_RATE.equals(finding.causeKind()) || callSiteId == null) {
            return new MalformedOutputEvidence.FailingOutputPage(List.of(), 0, null);
        }
        MalformedOutputRateRepository.DetectionPage page = rates.failingOutputs(
                finding.projectId(), finding.subjectId(), callSiteId, finding.onsetAt(), field, limit, cursor);
        List<MalformedOutputEvidence.FailingOutputView> rows =
                new ArrayList<>(page.rows().size());
        for (MalformedOutputRateRepository.DetectionRow d : page.rows()) {
            rows.add(toRow(finding.projectId(), d, field));
        }
        return new MalformedOutputEvidence.FailingOutputPage(List.copyOf(rows), page.total(), page.nextCursor());
    }

    private List<MalformedOutputEvidence.SchemaFieldView> schemaFields(
            String projectId, String callSiteId, Map<String, Long> counts) {
        String schemaJson =
                schemas.callSiteOutputSchemas(projectId, Set.of(callSiteId)).get(callSiteId);
        if (schemaJson == null) return List.of();
        JsonNode schema;
        try {
            schema = mapper.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            return List.of();
        }
        List<MalformedOutputEvidence.SchemaFieldView> out = new ArrayList<>();
        for (MalformedOutputSchema.Field f : MalformedOutputSchema.flatten(schema, counts)) {
            out.add(new MalformedOutputEvidence.SchemaFieldView(
                    f.path(), f.name(), f.type(), f.required(), f.depth(), f.failing()));
        }
        return List.copyOf(out);
    }

    /** Every trace a WITNESS ref names, in the order the classifier wrote them, deduplicated. */
    private List<String> witnessTraceIds(FindingRow finding) {
        List<String> out = new ArrayList<>();
        for (FindingEvidenceRow row : evidence.listByFinding(finding.projectId(), finding.id())) {
            if (FindingEvidenceRow.Role.WITNESS.equals(row.role())
                    && row.traceId() != null
                    && !out.contains(row.traceId())) {
                out.add(row.traceId());
            }
        }
        return List.copyOf(out);
    }

    private MalformedOutputEvidence.FailingOutputView toRow(
            String projectId, MalformedOutputRateRepository.DetectionRow d, String field) {
        String message = MalformedOutputEvidence.messageForField(d.evidenceJson(), field);
        SpanPayloadRow payload =
                payloads.find(projectId, d.traceId(), d.spanId()).orElse(null);
        String rawOutput = payload == null ? null : payload.output();
        if (rawOutput == null) {
            return new MalformedOutputEvidence.FailingOutputView(
                    d.traceId(), d.spanId(), d.name(), d.startedAt(), null, List.of(), message);
        }

        String toValidate = rawOutput;
        JsonNode node;
        try {
            node = mapper.readTree(rawOutput);
            String assistantText = MalformedOutputDetector.assistantTextFromMessageEnvelope(node);
            if (assistantText != null) {
                toValidate = assistantText;
                node = assistantText.isBlank() ? null : mapper.readTree(assistantText);
            }
        } catch (JsonProcessingException e) {
            node = null;
        }

        boolean isJson = node != null;
        String pretty = node != null ? node.toPrettyString() : toValidate;

        List<Integer> highlightLines = List.of();
        if (isJson
                && !MalformedOutputRateRepository.FIELD_NOT_JSON.equals(field)
                && !MalformedOutputRateRepository.FIELD_OTHER.equals(field)) {
            highlightLines = MalformedOutputHighlight.forField(mapper, pretty, field);
        }

        boolean truncated = pretty.length() > MAX_DOCUMENT_CHARS;
        String shown = truncated ? pretty.substring(0, MAX_DOCUMENT_CHARS) : pretty;
        if (truncated) {
            int shownLines = countLines(shown);
            highlightLines =
                    highlightLines.stream().filter(l -> l <= shownLines).toList();
        }
        String document = CredentialMasking.mask(shown);
        return new MalformedOutputEvidence.FailingOutputView(
                d.traceId(), d.spanId(), d.name(), d.startedAt(), document, highlightLines, message);
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }
}
