// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The records of a {@link Pipeline} bundle read back from the JSON the analysis agent wrote (the same
 * {@code new ObjectMapper()} semantics as the app's primary mapper). Each record fills what the agent
 * left out with an empty list or its documented default, and keeps what the agent did write: a record
 * read from a fully written fragment serializes back to that same fragment.
 */
class PipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Arguments> sparse() {
        return Stream.of(
                Arguments.of("an empty bundle", "{}", Pipeline.empty()),
                Arguments.of(
                        "a pack with only its identity",
                        "{\"id\":\"p1\",\"name\":\"Support\",\"version\":\"1.0\"}",
                        new Pack("p1", "Support", "1.0", null, null, Map.of(), List.of(), null)),
                Arguments.of(
                        "an invariant coverage with only the invariant",
                        "{\"invariant\":\"refunds need approval\"}",
                        new InvariantCoverage("refunds need approval", List.of(), List.of())),
                Arguments.of(
                        "a signal labelled under the alias 'kind', with no evidence",
                        "{\"kind\":\"pii\"}",
                        new EvidencedSignal("pii", "")),
                Arguments.of(
                        "a constraint with no enforcement",
                        "{\"kind\":\"format\",\"description\":\"json only\"}",
                        new Constraint("format", "json only", "deterministic")),
                Arguments.of(
                        "a chain with no member lists",
                        "{\"id\":\"c1\",\"name\":\"refund flow\",\"detection_method\":\"ensemble\","
                                + "\"confidence\":\"high\",\"rationale\":\"r\"}",
                        new Chain("c1", "refund flow", List.of(), "ensemble", "high", "r", List.of())),
                Arguments.of(
                        "a failure mode with only its identity",
                        "{\"id\":\"f1\",\"name\":\"wrong refund\",\"description\":\"d\"}",
                        new FailureMode(
                                "f1",
                                "wrong refund",
                                "d",
                                "medium",
                                "single_call",
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                false,
                                null)),
                Arguments.of(
                        "an implicit invariant with only its identity",
                        "{\"name\":\"n\",\"description\":\"d\",\"confidence\":\"high\"}",
                        new ImplicitInvariant("n", "d", "high", List.of(), "all_call_sites", List.of(), List.of())),
                Arguments.of(
                        "a taxonomy node with no examples or children",
                        "{\"id\":\"t1\",\"name\":\"n\",\"description\":\"d\",\"kind\":\"category\"}",
                        new TaxonomyNode("t1", "n", "d", null, List.of(), List.of(), "category", List.of())),
                Arguments.of(
                        "a product profile with nothing in it",
                        "{}",
                        new ProductProfile(null, List.of(), null, List.of(), List.of(), List.of(), List.of())));
    }

    /**
     * The bugs: a bundle whose agent omitted an optional list or field reads back with null where the
     * model promises an empty list or a default, and a consumer iterating it throws; a signal the agent
     * labelled under an alias is lost.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sparse")
    void whatTheAgentOmittedReadsBackAsItsDefault(String fragment, String json, Object expected) throws Exception {
        assertEquals(expected, MAPPER.readValue(json, expected.getClass()), fragment);
    }

    static Stream<Arguments> full() {
        return Stream.of(
                Arguments.of(
                        Pack.class,
                        "{\"id\":\"p1\",\"name\":\"Support\",\"version\":\"1.0\",\"tier_hint\":\"free\","
                                + "\"enabled_by\":\"auto\",\"interview_answers\":{\"region\":\"eu\"},"
                                + "\"contributes_compliance_tags\":[\"gdpr\"],\"content_digest\":\"sha256:ab\"}"),
                Arguments.of(
                        InvariantCoverage.class,
                        "{\"invariant\":\"refunds need approval\",\"enforced_in\":[\"billing.py\"],"
                                + "\"likely_gap_in\":[\"chat.py\"]}"),
                Arguments.of(EvidencedSignal.class, "{\"label\":\"hipaa\",\"evidence\":\"mentions PHI\"}"),
                Arguments.of(
                        Constraint.class,
                        "{\"kind\":\"format\",\"description\":\"json only\",\"enforcement\":\"llm_judge\"}"),
                Arguments.of(
                        Chain.class,
                        "{\"id\":\"c1\",\"name\":\"refund flow\",\"call_site_ids\":[\"cs1\"],"
                                + "\"detection_method\":\"ensemble\",\"confidence\":\"high\",\"rationale\":\"r\","
                                + "\"ensemble_span_ids\":[\"s1\"]}"),
                Arguments.of(
                        FailureMode.class,
                        "{\"id\":\"f1\",\"name\":\"wrong refund\",\"description\":\"d\",\"severity\":\"high\","
                                + "\"scope\":\"chain\",\"call_site_id\":\"cs1\",\"chain_id\":\"c1\",\"layer\":\"B\","
                                + "\"pack_ids\":[\"p1\"],\"compliance_tags\":[\"sox\"],\"taxonomy_node_id\":\"t1\","
                                + "\"grader_deferred\":true,\"grader_id\":\"g1\"}"),
                Arguments.of(
                        ImplicitInvariant.class,
                        "{\"name\":\"n\",\"description\":\"d\",\"confidence\":\"high\",\"evidence\":[\"e\"],"
                                + "\"applies_to\":[\"cs1\"],\"enforced_in\":[\"a.py\"],\"likely_gap_in\":[\"b.py\"]}"),
                Arguments.of(
                        TaxonomyNode.class,
                        "{\"id\":\"t1\",\"name\":\"n\",\"description\":\"d\",\"parent_id\":\"t0\","
                                + "\"example_call_site_ids\":[\"cs1\"],\"example_chain_ids\":[\"c1\"],"
                                + "\"kind\":\"category\",\"subcategories\":[{\"id\":\"t2\",\"name\":\"m\","
                                + "\"description\":\"e\",\"parent_id\":\"t1\",\"example_call_site_ids\":[\"cs2\"],"
                                + "\"example_chain_ids\":[\"c2\"],\"kind\":\"leaf\",\"subcategories\":[]}]}"),
                Arguments.of(
                        ProductProfile.class,
                        "{\"domain\":\"fintech\",\"user_types\":[{\"role\":\"agent\",\"surface\":\"web\","
                                + "\"constraints\":\"verified\",\"evidence\":\"auth.py\"}],\"business_model\":\"b2b\","
                                + "\"data_sensitivity\":[{\"label\":\"pii\",\"evidence\":\"e1\"}],"
                                + "\"regulatory_context\":[{\"label\":\"sox\",\"evidence\":\"e2\"}],"
                                + "\"brand_voice_signals\":[{\"label\":\"formal\",\"evidence\":\"e3\"}],"
                                + "\"notable_dependencies\":[\"stripe\"]}"),
                Arguments.of(
                        Observed.class,
                        "{\"first_seen\":\"2026-01-01\",\"last_seen\":\"2026-02-01\",\"error_rate\":0.1,"
                                + "\"refusal_rate\":0.2,\"p50_latency_ms\":300,\"p95_latency_ms\":900,"
                                + "\"p50_tokens_in\":100,\"p95_tokens_in\":400,\"p95_tokens_out\":250,"
                                + "\"cost_estimate_usd\":1.5,\"redaction_state\":\"partial\"}"),
                Arguments.of(
                        SourceSpan.class,
                        "{\"trace_id\":\"t\",\"span_id\":\"s\",\"parent_span_id\":\"p\",\"service_name\":\"api\","
                                + "\"timestamp\":\"2026-01-01T00:00:00Z\"}"));
    }

    /**
     * The bug: a default overwrites a value the agent did write (a present enforcement, compliance tag or
     * interview answer), so the bundle stored is not the bundle produced.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("full")
    void whatTheAgentWroteIsKept(Class<?> type, String json) throws Exception {
        assertEquals(MAPPER.readTree(json), MAPPER.valueToTree(MAPPER.readValue(json, type)), type.getSimpleName());
    }
}
