// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.detection.DetectionTable.Grain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DetectionTableRegistryTest {

    @Test
    void twoBeansClaimingOneKindFailAtConstruction() {
        ObjectProvider<DetectionTable> dup = Providers.of(
                new DetectionTable("classifier", "user_classifier_detection", Grain.SPAN, false, false),
                new DetectionTable("classifier", "some_other_table", Grain.SPAN, false, false));

        assertThrows(IllegalStateException.class, () -> new DetectionTableRegistry(dup));
    }

    @Test
    void badTableNameIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetectionTable("secret_leak", "Secret Leak; DROP TABLE x", Grain.SPAN, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetectionTable("k", "1_leading_digit", Grain.SPAN, false, false));
    }

    @Test
    void unionSqlHasOneArmPerDistinctTableAndThirteenColumnsPerArm() {
        DetectionTableRegistry registry = new DetectionTableRegistry(Providers.of(
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false),
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE, false, false),
                new DetectionTable("classifier", "user_classifier_detection", Grain.SPAN, false, false),
                new DetectionTable("regex", "user_classifier_detection", Grain.SPAN, false, false)));

        String sql = registry.unionSql();
        String[] arms = sql.split(" UNION ALL ");
        assertEquals(3, arms.length, "four kinds, two sharing a table, is three distinct arms");
        for (String arm : arms) {
            // 13 columns: id, project_id, classifier_id, severity, confidence, subject_kind,
            // subject_session_id, subject_trace_id, subject_span_id, evidence, project_version_id,
            // created_at, subject_started_at — count the commas between SELECT and FROM.
            String selectList = arm.substring(arm.indexOf("SELECT") + "SELECT".length(), arm.indexOf(" FROM "));
            assertEquals(12, selectList.chars().filter(c -> c == ',').count(), "13 columns => 12 commas: " + arm);
        }
        assertTrue(sql.contains("'trace'::text AS subject_kind"));
        assertTrue(sql.contains("'span'::text AS subject_kind"));
    }
}
