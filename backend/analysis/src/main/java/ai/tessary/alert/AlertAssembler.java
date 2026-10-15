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
 * Rolls up a project's detection activity over a period into a digest or brief {@link AlertEventRow}; the two differ
 * only in {@code rule_type} and cron. The worker owns the idempotent write.
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
     * {@code window_start} is the idempotency key, so a period rolls up once. Empty when there was no activity: an
     * empty digest is not fired.
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
