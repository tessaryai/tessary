// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.detection.DetectionTable.Grain;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DetectionTableRegistryTest {

    /** A minimal {@link ObjectProvider} stub backed by a fixed list, mirroring what Spring hands a
     * bean constructor for an {@code ObjectProvider<T>} parameter — only {@code orderedStream()} is
     * exercised by {@link DetectionTableRegistry}, so nothing else needs a real implementation. */
    private static <T> ObjectProvider<T> providerOf(List<T> items) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("not exercised by DetectionTableRegistry");
            }

            @Override
            public Stream<T> orderedStream() {
                return items.stream();
            }
        };
    }

    @Test
    void tableForAndWritesDetectionsResolveARegisteredKind() {
        DetectionTableRegistry registry = new DetectionTableRegistry(providerOf(List.of(
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN),
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE))));

        assertEquals("secret_leak_detection", registry.tableFor("secret_leak"));
        assertTrue(registry.writesDetections("frustration"));
        assertNull(registry.tableFor("no_such_kind"));
        assertFalse(registry.writesDetections("no_such_kind"));
    }

    @Test
    void twoBeansClaimingOneKindFailAtConstruction() {
        List<DetectionTable> dup = List.of(
                new DetectionTable("classifier", "user_classifier_detection", Grain.SPAN),
                new DetectionTable("classifier", "some_other_table", Grain.SPAN));

        assertThrows(IllegalStateException.class, () -> new DetectionTableRegistry(providerOf(dup)));
    }

    @Test
    void badTableNameIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DetectionTable("secret_leak", "Secret Leak; DROP TABLE x", Grain.SPAN));
        assertThrows(IllegalArgumentException.class, () -> new DetectionTable("k", "1_leading_digit", Grain.SPAN));
    }

    @Test
    void twoKindsMaySharedOneTable() {
        DetectionTableRegistry registry = new DetectionTableRegistry(providerOf(List.of(
                new DetectionTable("classifier", "user_classifier_detection", Grain.SPAN),
                new DetectionTable("regex", "user_classifier_detection", Grain.SPAN))));

        // Deduplicated by table name: one arm, not two, in the union.
        assertEquals(1, registry.tables().size());
        assertEquals("user_classifier_detection", registry.tables().get(0).table());
    }

    @Test
    void unionSqlHasOneArmPerDistinctTableAndTwelveColumnsPerArm() {
        DetectionTableRegistry registry = new DetectionTableRegistry(providerOf(List.of(
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN),
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE),
                new DetectionTable("classifier", "user_classifier_detection", Grain.SPAN),
                new DetectionTable("regex", "user_classifier_detection", Grain.SPAN))));

        String sql = registry.unionSql();
        String[] arms = sql.split(" UNION ALL ");
        assertEquals(3, arms.length, "four kinds, two sharing a table, is three distinct arms");
        for (String arm : arms) {
            // 12 columns: id, project_id, classifier_id, severity, confidence, subject_kind,
            // subject_session_id, subject_trace_id, subject_span_id, evidence, project_version_id,
            // created_at — count the commas between SELECT and FROM.
            String selectList = arm.substring(arm.indexOf("SELECT") + "SELECT".length(), arm.indexOf(" FROM "));
            assertEquals(11, selectList.chars().filter(c -> c == ',').count(), "12 columns => 11 commas: " + arm);
        }
        assertTrue(sql.contains("'trace'::text AS subject_kind"));
        assertTrue(sql.contains("'span'::text AS subject_kind"));
    }

    @Test
    void unionSqlWithNoRegistrationsIsAWellTypedEmptyRelation() {
        DetectionTableRegistry registry = new DetectionTableRegistry(providerOf(List.of()));
        assertEquals("SELECT NULL WHERE false", registry.unionSql());
        assertTrue(registry.tables().isEmpty());
    }
}
