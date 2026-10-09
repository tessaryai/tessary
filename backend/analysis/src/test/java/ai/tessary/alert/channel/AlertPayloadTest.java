// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.AlertRuleRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The text a person reads and the envelope a receiver parses. The bugs: a case message that drops the
 * detector's basis or the link, prints a blank or null field as the literal text, or renders for a rule
 * type it was not written for; and an envelope that invents fields a case firing does not have.
 */
class AlertPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aCaseMessageCarriesTitleDetectorCallSiteBasisRulingAndLink() {
        AlertEventRow e = event(
                AlertRuleRow.RuleType.CASE_OPENED,
                null,
                "{\"case_id\":\"c1\",\"case_reference\":\"C-7\",\"detector\":\"cost_drift\","
                        + "\"title\":\"Cost per turn up 40%\",\"basis\":\"p95 cost crossed its learned band\","
                        + "\"call_site_id\":\"cs_answer\",\"ruled_by\":\"a triage run\","
                        + "\"ruling_summary\":\"The model swap doubled output tokens.\","
                        + "\"url\":\"https://app.example/orgs/o/projects/p/cases/c1\"}");

        assertEquals(
                "*C-7* · Cost per turn up 40%\n"
                        + "_cost drift · `cs_answer`_\n"
                        + "p95 cost crossed its learned band\n"
                        + "Confirmed by a triage run. The model swap doubled output tokens.\n"
                        + "https://app.example/orgs/o/projects/p/cases/c1",
                AlertPayload.caseMessage(e, mapper));
        assertEquals("C-7 · Cost per turn up 40%", AlertPayload.summary(e, mapper));
    }

    /** Absent, null and blank fields fall back to words, never to "null" or an empty line. */
    @Test
    void aSparseCaseMessageFallsBackRatherThanPrintingNull() {
        AlertEventRow e = event(
                AlertRuleRow.RuleType.CASE_OPENED,
                null,
                "{\"detector\":\"tool_error\",\"title\":\"  \",\"call_site_id\":null,"
                        + "\"basis\":\"error rate 3x baseline\",\"ruled_by\":\"a person\"}");

        assertEquals(
                "*A case* · a case opened\n_tool error_\nerror rate 3x baseline\nConfirmed by a person.",
                AlertPayload.caseMessage(e, mapper));
        assertEquals("A case opened", AlertPayload.summary(e, mapper));
    }

    /** A roll-up has no case message, so Slack falls back to the one-line summary with its own label. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"digest, Daily digest", "brief, Scheduled brief"})
    void aRollupHasNoCaseMessageAndALabelledSummary(String ruleType, String label) {
        AlertEventRow e = event(ruleType, 42, "{\"classifiers\":[]}");

        assertNull(AlertPayload.caseMessage(e, mapper));
        assertEquals(
                label + ": 42 events in 2026-01-15T09:00:00Z … 2026-01-15T10:00:00Z", AlertPayload.summary(e, mapper));
    }

    /** A case firing's envelope names its case and carries no observed value or payload it does not have. */
    @Test
    void aCaseEnvelopeCarriesTheCaseIdAndNoInventedFields() throws Exception {
        AlertEventRow e = event(AlertRuleRow.RuleType.CASE_OPENED, null, null);

        assertEquals(
                mapper.readTree("{\"schema_version\":\"1\",\"event_id\":\"evt1\",\"project_id\":\"p1\","
                        + "\"alert_id\":\"rule1\",\"kind\":\"case_opened\",\"fired_at\":\"2026-01-15T10:00:01Z\","
                        + "\"window_start\":\"2026-01-15T09:00:00Z\",\"window_end\":\"2026-01-15T10:00:00Z\","
                        + "\"case_id\":\"case1\"}"),
                AlertPayload.envelope(e, mapper));
    }

    /**
     * A corrupt stored payload fails the delivery loudly, and the dispatcher records it as a failed attempt.
     * Rendering it as an empty object would send "A case opened" with no detail and nothing in the log.
     */
    @Test
    void aCorruptPayloadFailsRatherThanRenderingAnEmptyMessage() {
        AlertEventRow e = event(AlertRuleRow.RuleType.CASE_OPENED, null, "{\"title\":");

        assertThrows(UncheckedIOException.class, () -> AlertPayload.summary(e, mapper));
    }

    private static AlertEventRow event(String ruleType, @Nullable Integer value, @Nullable String payloadJson) {
        return new AlertEventRow(
                "evt1",
                "p1",
                "rule1",
                null,
                ruleType,
                null,
                AlertEventRow.State.FIRING,
                "2026-01-15T09:00:00Z",
                "2026-01-15T10:00:00Z",
                value,
                null,
                payloadJson,
                AlertRuleRow.RuleType.CASE_OPENED.equals(ruleType) ? "case1" : null,
                "2026-01-15T10:00:01Z",
                "2026-01-15T10:00:01Z");
    }
}
