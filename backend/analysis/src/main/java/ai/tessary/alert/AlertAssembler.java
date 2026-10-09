// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Rolls up a project's detection activity over a period into a digest or brief {@link AlertEventRow}.
 * A digest and a brief are the SAME assembly — a per-classifier count summary over
 * {@code [windowStart, windowEnd)} — differing only in the roll-up rule's {@code rule_type} and the cron
 * that schedules them. Side-effect-free apart from its read; the worker owns the idempotent write + event.
 */
@Component
public class AlertAssembler {

    private final AlertQueryRepository queries;
    private final ObjectMapper mapper;

    public AlertAssembler(AlertQueryRepository queries, ObjectMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    /**
     * Build a roll-up firing for a roll-up {@code rule} over {@code [windowStart, windowEnd)}. The firing's
     * {@code alert_rule_id} is the rule's id (a real FK) and {@code window_start} is the idempotency key, so
     * the same period rolls up exactly once. Returns empty when there was no activity in the period (an empty
     * digest/brief is not fired — nothing to report).
     */
    public Optional<AlertEventRow> assemble(AlertRuleRow rule, Instant windowStart, Instant windowEnd) {
        String start = windowStart.toString();
        String end = windowEnd.toString();
        List<AlertQueryRepository.ClassifierActivity> activity =
                queries.projectActivityInWindow(rule.projectId(), start, end);
        if (activity.isEmpty()) {
            return Optional.empty();
        }

        long total = activity.stream()
                .mapToLong(AlertQueryRepository.ClassifierActivity::eventCount)
                .sum();
        AlertEventRow row = new AlertEventRow(
                Ids.ulid(),
                rule.projectId(),
                rule.id(),
                null, // a roll-up spans all classifiers, not one
                rule.ruleType(),
                null, // roll-ups are not threshold breaches — no basis
                AlertEventRow.State.FIRING,
                start,
                end,
                (int) Math.min(Integer.MAX_VALUE, total),
                null,
                writeJson(rule.ruleType(), activity),
                null, // a roll-up is about a period, not a case
                end,
                end);
        return Optional.of(row);
    }

    private String writeJson(String ruleType, List<AlertQueryRepository.ClassifierActivity> activity) {
        ObjectNode body = mapper.createObjectNode();
        body.put("rule_type", ruleType);
        ArrayNode classifiers = body.putArray("classifiers");
        for (AlertQueryRepository.ClassifierActivity a : activity) {
            ObjectNode m = classifiers.addObject();
            m.put("classifier_key", a.classifierKey());
            m.put("name", a.classifierName());
            m.put("event_count", a.eventCount());
        }
        return body.toString();
    }
}
