// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import ai.tessary.alert.AlertQueryRepository.OpenedCase;
import ai.tessary.config.AlertProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cases into firings. The payload is baked at fire time and is everything the Slack message renders, so
 * the bugs are all in it: a withdrawn detector's case paged about, the unattributed bucket printed as a
 * call site, a Layer-2 summary shown under a human ruling, and a link built on a double slash or with
 * unencoded slugs.
 */
@ExtendWith(MockitoExtension.class)
class CaseAlertEvaluatorTest {

    private static final Instant SINCE = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    AlertQueryRepository queries;

    /** Both spellings of the base URL build the same link, with the slugs percent-encoded. */
    @ParameterizedTest(name = "base {0}")
    @ValueSource(strings = {"https://app.example/", "https://app.example"})
    void eachDeliverableCaseBecomesOneFiringWithItsWholeMessage(String baseUrl) throws Exception {
        when(queries.casesOpenedBetween("p1", SINCE.toString(), NOW.toString(), CaseAlertEvaluator.PER_TICK_CAP))
                .thenReturn(List.of(
                        opened("c1", "cost_drift", "cs_answer", "real", "The model swap doubled tokens.", false),
                        opened("c2", "tool_error", "__unattributed__", null, null, false),
                        opened("c3", "frustration", "cs_chat", "real", "withdrawn detector", false),
                        opened("c4", "duration_drift", "  ", "real", "a human ruled, so not shown", true)));

        List<AlertEventRow> firings = evaluator(baseUrl).due(rule(), SINCE, NOW, Set.of("frustration"));

        assertEquals(
                List.of("c1", "c2", "c4"),
                firings.stream().map(AlertEventRow::caseId).toList());
        AlertEventRow first = firings.get(0);
        assertEquals(
                new AlertEventRow(
                        first.id(),
                        "p1",
                        "rule1",
                        null,
                        AlertRuleRow.RuleType.CASE_OPENED,
                        null,
                        AlertEventRow.State.FIRING,
                        "2026-01-15T09:30:00Z",
                        "2026-01-15T09:30:00Z",
                        null,
                        null,
                        first.payloadJson(),
                        "c1",
                        NOW.toString(),
                        NOW.toString()),
                first);
        assertEquals(
                mapper.readTree("{\"case_id\":\"c1\",\"case_reference\":\"C-1\",\"detector\":\"cost_drift\","
                        + "\"title\":\"t-c1\",\"basis\":\"b-c1\",\"opened_at\":\"2026-01-15T09:30:00Z\","
                        + "\"call_site_id\":\"cs_answer\",\"ruled_by\":\"a triage run\","
                        + "\"ruling_summary\":\"The model swap doubled tokens.\","
                        + "\"url\":\"https://app.example/orgs/acme+co/projects/bot%2F1/cases/c1\"}"),
                mapper.readTree(first.payloadJson()));
        assertEquals(
                mapper.readTree("{\"case_id\":\"c2\",\"case_reference\":\"C-2\",\"detector\":\"tool_error\","
                        + "\"title\":\"t-c2\",\"basis\":\"b-c2\",\"opened_at\":\"2026-01-15T09:30:00Z\","
                        + "\"ruled_by\":\"a Layer-2 run\","
                        + "\"url\":\"https://app.example/orgs/acme+co/projects/bot%2F1/cases/c2\"}"),
                mapper.readTree(firings.get(1).payloadJson()));
        assertEquals(
                mapper.readTree("{\"case_id\":\"c4\",\"case_reference\":\"C-4\",\"detector\":\"duration_drift\","
                        + "\"title\":\"t-c4\",\"basis\":\"b-c4\",\"opened_at\":\"2026-01-15T09:30:00Z\","
                        + "\"ruled_by\":\"a person\","
                        + "\"url\":\"https://app.example/orgs/acme+co/projects/bot%2F1/cases/c4\"}"),
                mapper.readTree(firings.get(2).payloadJson()));
    }

    /** With no public base URL there is no link at all, rather than a relative path that goes nowhere. */
    @Test
    void noBaseUrlMeansNoLink() throws Exception {
        when(queries.casesOpenedBetween("p1", SINCE.toString(), NOW.toString(), CaseAlertEvaluator.PER_TICK_CAP))
                .thenReturn(List.of(opened("c1", "cost_drift", null, "real", " ", false)));

        List<AlertEventRow> firings = evaluator("").due(rule(), SINCE, NOW, Set.of());

        assertEquals(
                mapper.readTree("{\"case_id\":\"c1\",\"case_reference\":\"C-1\",\"detector\":\"cost_drift\","
                        + "\"title\":\"t-c1\",\"basis\":\"b-c1\",\"opened_at\":\"2026-01-15T09:30:00Z\","
                        + "\"ruled_by\":\"a triage run\"}"),
                mapper.readTree(firings.get(0).payloadJson()));
    }

    private CaseAlertEvaluator evaluator(String baseUrl) {
        AlertProperties props = new AlertProperties();
        props.setAppBaseUrl(baseUrl);
        return new CaseAlertEvaluator(queries, props, mapper);
    }

    private static OpenedCase opened(
            String id,
            String detector,
            @Nullable String callSite,
            @Nullable String verdict,
            @Nullable String summary,
            boolean human) {
        return new OpenedCase(
                id,
                "C-" + id.substring(1),
                detector,
                "t-" + id,
                "b-" + id,
                callSite,
                "2026-01-15T09:30:00Z",
                verdict,
                summary,
                human,
                "acme co",
                "bot/1");
    }

    private static AlertRuleRow rule() {
        return new AlertRuleRow(
                "rule1",
                "p1",
                AlertRuleRow.RuleType.CASE_OPENED,
                "A case opens",
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
                true,
                null,
                SINCE.toString(),
                null,
                null,
                "{}",
                SINCE.toString(),
                SINCE.toString());
    }
}
