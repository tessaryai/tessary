// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code alert_rule} writes, every column. The bugs: a column dropped or swapped on insert or update, and
 * an update that touches what it must preserve ({@code enabled}, {@code snoozed_until}, the three anchors,
 * {@code created_at}), which would re-page a partner about everything since the rule was made.
 */
@SpringBootTest
class AlertRuleRepositoryTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ClassifierRepository classifiers;

    @Autowired
    AlertRuleRepository rules;

    @Test
    void everyColumnRoundTripsAndAnUpdatePreservesSwitchesAnchorsAndCreation() {
        var fix = TenantFixture.bootstrap(tenants, "alert-rule-repo");
        String pid = fix.project().id();
        String cls = Ids.ulid();
        classifiers.insert(new ClassifierRow(
                cls,
                pid,
                "k-" + cls,
                "k",
                null,
                "keyword",
                null,
                false,
                1,
                true,
                ClassifierRow.Mode.DISCOVERY,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"));
        AlertRuleRow inserted = new AlertRuleRow(
                Ids.ulid(),
                pid,
                AlertRuleRow.RuleType.THRESHOLD,
                "t1",
                cls,
                "span",
                "event_count",
                3,
                600,
                "call_site",
                5,
                "warn",
                "0 0 8 * * *",
                "0 0 9 * * MON",
                false,
                "2030-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "2026-01-03T00:00:00Z",
                "2026-01-04T00:00:00Z",
                "{\"k\": \"v\"}",
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:01Z");

        rules.insert(inserted);

        assertEquals(Optional.of(inserted), rules.findById(pid, inserted.id()));
        assertEquals(Optional.of(inserted), rules.findThresholdBySignal(pid, cls));

        rules.update(new AlertRuleRow(
                inserted.id(),
                pid,
                AlertRuleRow.RuleType.THRESHOLD,
                "t2",
                cls,
                "trace",
                "distinct_users",
                7,
                60,
                "model",
                9,
                "page",
                "0 30 7 * * *",
                "0 0 10 * * FRI",
                true,
                null,
                null,
                null,
                null,
                "{\"k\": \"w\"}",
                "2027-01-01T00:00:00Z",
                "2026-02-01T00:00:00Z"));

        assertEquals(
                Optional.of(new AlertRuleRow(
                        inserted.id(),
                        pid,
                        AlertRuleRow.RuleType.THRESHOLD,
                        "t2",
                        cls,
                        "trace",
                        "distinct_users",
                        7,
                        60,
                        "model",
                        9,
                        "page",
                        "0 30 7 * * *",
                        "0 0 10 * * FRI",
                        false,
                        "2030-01-01T00:00:00Z",
                        "2026-01-02T00:00:00Z",
                        "2026-01-03T00:00:00Z",
                        "2026-01-04T00:00:00Z",
                        "{\"k\": \"w\"}",
                        "2026-01-01T00:00:00Z",
                        "2026-02-01T00:00:00Z")),
                rules.findById(pid, inserted.id()));
        assertEquals(Optional.empty(), rules.findById("another-project", inserted.id()), "reads are project-scoped");
    }
}
