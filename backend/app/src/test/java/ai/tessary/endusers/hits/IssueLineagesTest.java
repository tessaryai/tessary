// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.hits;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An issue is a case lineage. A cause that opens a fresh case with the same key must stay one issue, or its user IDs
 * hit under the first case would count as controls of the second.
 */
@SpringBootTest
record IssueLineagesTest(TenantService tenants, JdbcClient jdbc, IssueLineages lineages) {

    private static final String TOOL = "{\"cause_kind\":\"rate_shift\"}";

    @Test
    void lineage_unionsFindingsOfReopenedCase() {
        String pid =
                TenantFixture.bootstrap(tenants, "lineage-reopened").project().id();
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.caseRow("c-1", 1, "tool_error", "tool", "tool:search", "error_rate", true, null);
        seed.caseRow("c-2", 2, "tool_error", "tool", "tool:search", "error_rate", false, null);
        for (String f : List.of("f-1", "f-2", "f-negative", "f-no-case")) seed.finding(f, "tool_error", TOOL);
        seed.attach("f-1", "c-1", "positive");
        seed.attach("f-2", "c-2", "positive");
        seed.attach("f-negative", "c-2", "negative");

        List<IssueLineages.IssueLineage> issues = lineages.lineages(pid);

        assertAll(
                () -> assertEquals(1, issues.size(), "two cases with one key are one lineage"),
                () -> assertEquals(List.of("f-1", "f-2"), issues.get(0).findingIds()),
                () -> assertEquals(List.of("c-1", "c-2"), issues.get(0).caseIds()),
                () -> assertEquals("c-2", issues.get(0).currentCaseId(), "the unresolved case is current"),
                () -> assertEquals(true, issues.get(0).complete()),
                () -> assertEquals(false, issues.get(0).metricShift()));
    }

    @Test
    void lineage_leavesOutCasesResolvedAsFalseAlarm_andKeysEachCaseKeyApart() {
        String pid = TenantFixture.bootstrap(tenants, "lineage-false-alarm")
                .project()
                .id();
        HitSeeds seed = new HitSeeds(jdbc, pid);
        seed.caseRow("c-alarm", 1, "frustration", "call_site", "cs-a", "frustration_rate", true, "false_alarm");
        seed.caseRow("c-a", 2, "tool_error", "tool", "tool:a", "error_rate", true, null);
        seed.caseRow("c-b", 3, "tool_error", "tool", "tool:b", "error_rate", true, null);
        seed.finding("f-alarm", "frustration", "{\"cause_kind\":\"frustration_rate\"}");
        seed.finding("f-a", "tool_error", TOOL);
        seed.finding("f-b", "paid_custom_rate", "{\"cause_kind\":\"custom_rate\"}");
        seed.attach("f-alarm", "c-alarm", "positive");
        seed.attach("f-a", "c-a", "positive");
        seed.attach("f-b", "c-b", "positive");

        List<IssueLineages.IssueLineage> issues = lineages.lineages(pid);

        assertAll(
                () -> assertEquals(
                        List.of("c-a", "c-b"),
                        issues.stream()
                                .map(IssueLineages.IssueLineage::currentCaseId)
                                .toList()),
                () -> assertEquals(
                        2,
                        issues.stream()
                                .map(IssueLineages.IssueLineage::lineageKey)
                                .distinct()
                                .count()),
                () -> assertEquals(
                        List.of(true, false),
                        issues.stream()
                                .map(IssueLineages.IssueLineage::complete)
                                .toList(),
                        "an unknown kind leaves its issue not complete"));
    }
}
