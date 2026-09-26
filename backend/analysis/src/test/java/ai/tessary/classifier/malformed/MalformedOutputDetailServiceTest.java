// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.finding.FindingEvidenceRepository;
import ai.tessary.classifier.finding.FindingEvidenceRow;
import ai.tessary.classifier.finding.FindingRow;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository.DetectionPage;
import ai.tessary.classifier.malformed.MalformedOutputRateRepository.DetectionRow;
import ai.tessary.classifier.substrate.CallSiteSchemaReads;
import ai.tessary.storage.SpanPayloadRepository;
import ai.tessary.storage.SpanPayloadRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * "How outputs broke" over hand-written stores, for edges the integration suite does not reach: an unreadable schema,
 * non-failing evidence refs, bucket fields, and a document cut between highlighted lines.
 */
class MalformedOutputDetailServiceTest {

    private final Map<String, String> schemas = new HashMap<>();
    private final FakeRates rates = new FakeRates();
    private final List<FindingEvidenceRow> evidenceRows = new java.util.ArrayList<>();
    private final Map<String, String> outputsBySpan = new HashMap<>();
    private final CallSiteSchemaReads schemaReads = (projectId, ids) ->
            ids.stream().filter(schemas::containsKey).collect(Collectors.toMap(id -> id, schemas::get));
    private final MalformedOutputDetailService service = new MalformedOutputDetailService(
            schemaReads,
            rates,
            new FindingEvidenceRepository(mock(JdbcClient.class)) {
                @Override
                public List<FindingEvidenceRow> listByFinding(String projectId, String findingId) {
                    return evidenceRows;
                }
            },
            new SpanPayloadRepository(mock(JdbcClient.class), mock(NamedParameterJdbcTemplate.class)) {
                @Override
                public Optional<SpanPayloadRow> find(String projectId, String traceId, String spanId) {
                    return Optional.ofNullable(outputsBySpan.get(spanId))
                            .map(out ->
                                    new SpanPayloadRow(projectId, traceId, spanId, null, out, null, null, "t", null));
                }
            },
            new ObjectMapper());

    /** Not built for another cause's finding, or a rate finding with no call site. */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"tool_error_rate, cs-a", "malformed_rate, NULL"})
    void aFindingThisBlockDoesNotDescribeGetsNoBlockAndNoRows(String causeKind, @Nullable String callSiteId) {
        FindingRow finding = finding(causeKind, callSiteId);

        assertNull(service.detail(finding));
        assertEquals(
                new MalformedOutputEvidence.FailingOutputPage(List.of(), 0, null),
                service.failingOutputs(finding, "answer", 10, null));
    }

    /**
     * An undeclared or unparseable schema still shows the buckets; the failing-trace list drops non-witness refs,
     * traceless session refs, and repeats.
     */
    @Test
    void anUnreadableSchemaLeavesTheTreeEmptyAndOnlyWitnessTracesAreListed() {
        rates.counts = Map.of("not_json", 4L, "other", 2L, "answer", 1L);
        evidenceRows.add(ref("t1", FindingEvidenceRow.Role.WITNESS));
        evidenceRows.add(ref("t2", FindingEvidenceRow.Role.EXEMPLAR));
        evidenceRows.add(ref(null, FindingEvidenceRow.Role.WITNESS));
        evidenceRows.add(ref("t1", FindingEvidenceRow.Role.WITNESS));
        evidenceRows.add(ref("t3", FindingEvidenceRow.Role.WITNESS));
        FindingRow finding = finding(FindingRow.Cause.MALFORMED_RATE, "cs-a");

        MalformedOutputEvidence.MalformedDetail undeclared = service.detail(finding);
        schemas.put("cs-a", "{not a schema");
        MalformedOutputEvidence.MalformedDetail unparseable = service.detail(finding);

        for (MalformedOutputEvidence.MalformedDetail d : List.of(undeclared, unparseable)) {
            assertNotNull(d);
            assertEquals(List.of(), d.fields());
            assertEquals(4, d.notJson());
            assertEquals(2, d.other());
            assertEquals(List.of("t1", "t3"), d.rate().failingTraces());
        }
    }

    /**
     * A bucket naming no field highlights nothing even in a parseable document; an envelope with no assistant text
     * renders the empty answer; a document cut at the cap keeps only on-screen highlight lines.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "not_json    | '{\"answer\":1}' | 18 | ''",
                "other       | '{\"answer\":1}' | 18 | ''",
                "answer      | '[{\"role\":\"assistant\",\"content\":\"\"}]' | 0 | ''",
                "items[].sku | LONG | 20000 | 3"
            })
    void aDocumentIsHighlightedOnlyForADeclaredFieldAndOnlyWhereItIsShown(
            String field, String output, int documentLength, String lines) {
        outputsBySpan.put("s1", "LONG".equals(output) ? skuFirstAndLast() : output);
        rates.rows = List.of(new DetectionRow("t1", "s1", "chat", "2026-09-01T00:00:00Z", "d1", null));

        MalformedOutputEvidence.FailingOutputView row = service.failingOutputs(
                        finding(FindingRow.Cause.MALFORMED_RATE, "cs-a"), field, 10, null)
                .rows()
                .get(0);

        assertEquals(
                documentLength, java.util.Objects.requireNonNull(row.document()).length());
        assertEquals(lines.isBlank() ? List.of() : List.of(Integer.valueOf(lines)), row.highlightLines());
    }

    /** {@code items} whose first and last elements carry a {@code sku}, padded past the cap. */
    private static String skuFirstAndLast() {
        StringBuilder sb = new StringBuilder("{\"items\":[{\"sku\":1}");
        for (int i = 0; i < 2_000; i++) sb.append(",{\"p\":\"xxxxxxxxxx\"}");
        return sb.append(",{\"sku\":2}]}").toString();
    }

    private static final class FakeRates extends MalformedOutputRateRepository {
        Map<String, Long> counts = Map.of();
        List<DetectionRow> rows = List.of();

        FakeRates() {
            super(mock(JdbcClient.class));
        }

        @Override
        public Map<String, Long> fieldFailureCounts(
                String projectId, String classifierId, String callSiteId, String since) {
            return counts;
        }

        @Override
        public DetectionPage failingOutputs(
                String projectId,
                String classifierId,
                String callSiteId,
                String since,
                String field,
                int limit,
                @Nullable String cursor) {
            return new DetectionPage(rows, rows.size(), null);
        }
    }

    private static FindingEvidenceRow ref(@Nullable String traceId, String role) {
        return new FindingEvidenceRow("e", "p", "f", null, traceId, null, role, null, "2026-09-01T00:00:00Z");
    }

    private static FindingRow finding(String causeKind, @Nullable String callSiteId) {
        return new FindingRow(
                "f",
                "p",
                "malformed_output",
                "cause:f",
                FindingRow.SubjectKind.CLASSIFIER,
                "clf",
                null,
                callSiteId,
                FindingRow.Status.OPEN,
                "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z",
                null,
                null,
                null,
                3,
                "{\"cause_kind\":\"" + causeKind + "\"}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z");
    }
}
