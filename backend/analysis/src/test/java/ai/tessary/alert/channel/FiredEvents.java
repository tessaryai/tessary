// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertEventRow;
import ai.tessary.alert.AlertRuleRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fired alerts for the connector tests, with the fixed instants their expected bodies are written against. */
final class FiredEvents {

    static final String WINDOW_START = "2026-01-15T09:00:00Z";
    static final String WINDOW_END = "2026-01-15T10:00:00Z";
    static final String FIRED_AT = "2026-01-15T10:00:01Z";

    private FiredEvents() {}

    /** A daily digest that rolled up {@code value} detections. */
    static AlertEventRow digest(int value) {
        return new AlertEventRow(
                "evt_digest",
                "p1",
                "rule_digest",
                null,
                AlertRuleRow.RuleType.DIGEST,
                null,
                AlertEventRow.State.FIRING,
                WINDOW_START,
                WINDOW_END,
                value,
                null,
                "{\"classifiers\":[]}",
                null,
                FIRED_AT,
                FIRED_AT);
    }

    /** A case-opened firing: no observed value, and its title in the payload. */
    static AlertEventRow caseOpened() {
        return new AlertEventRow(
                "evt_case",
                "p1",
                "rule_case",
                null,
                AlertRuleRow.RuleType.CASE_OPENED,
                null,
                AlertEventRow.State.FIRING,
                WINDOW_START,
                WINDOW_START,
                null,
                null,
                "{\"case_reference\":\"C-3\",\"title\":\"Latency up 2x\",\"detector\":\"duration_drift\","
                        + "\"basis\":\"p95 above band\",\"ruled_by\":\"a person\"}",
                "case1",
                FIRED_AT,
                FIRED_AT);
    }

    static JsonNode config(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }
}
