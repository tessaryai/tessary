// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Fails boot, naming kind and table, when a registered {@link DetectionTable} has no relation. Runs from
 * {@link #afterSingletonsInstantiated()}, after every Liquibase bean has applied, as long as none is {@code @Lazy}.
 * No-op without a {@code DataSource}.
 */
@Component
public class DetectionTableSchemaCheck implements SmartInitializingSingleton {

    private final DetectionTableRegistry registry;
    private final ObjectProvider<DataSource> dataSource;

    public DetectionTableSchemaCheck(DetectionTableRegistry registry, ObjectProvider<DataSource> dataSource) {
        this.registry = registry;
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return;
        }
        for (DetectionTable table : registry.tables()) {
            assertExists(ds, table);
        }
    }

    /**
     * Added by migration {@code 0012}; an overlay's tables must carry it too. Checked here because a missing column
     * would otherwise surface as a classifier silently never writing.
     */
    private static final String SUBJECT_STARTED_AT = "subject_started_at";

    /** A reader filtering on it fails on a table without it. */
    private static final String CLEARED_AT = "cleared_at";

    private void assertExists(DataSource ds, DetectionTable table) {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT to_regclass('" + table.table() + "') IS NOT NULL")) {
            if (!rs.next() || !rs.getBoolean(1)) {
                throw new IllegalStateException(
                        "DetectionTable registered for kind '" + table.detectorKind() + "' names table '"
                                + table.table()
                                + "', which does not exist in the connected database — its owning module's"
                                + " Liquibase changelog must run before this check does");
            }
            assertHasColumn(stmt, table, SUBJECT_STARTED_AT);
            if (table.clearable()) {
                assertHasColumn(stmt, table, CLEARED_AT);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "failed to verify detection table '" + table.table() + "' for kind '" + table.detectorKind()
                            + "' exists",
                    e);
        }
    }

    private static void assertHasColumn(Statement stmt, DetectionTable table, String column) throws SQLException {
        try (var rs = stmt.executeQuery("SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = '" + table.table() + "' AND column_name = '"
                + column + "')")) {
            if (!rs.next() || !rs.getBoolean(1)) {
                throw new IllegalStateException("DetectionTable registered for kind '" + table.detectorKind()
                        + "' names table '" + table.table() + "', which has no '" + column + "' column — "
                        + (CLEARED_AT.equals(column)
                                ? "it is registered clearable, so its owning module's changelog must add the column"
                                : "its owning module's Liquibase changelog is behind migration 0012"));
            }
        }
    }
}
