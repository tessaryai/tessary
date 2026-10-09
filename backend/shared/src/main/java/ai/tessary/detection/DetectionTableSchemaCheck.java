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
 * Fails boot loudly, naming the kind and the table, when a registered {@link DetectionTable} points
 * at a relation that does not exist in the connected database.
 *
 * <p>Runs from {@link #afterSingletonsInstantiated()}, which Spring Boot calls after every singleton
 * bean has been fully initialized, including any {@code SpringLiquibase} bean, whose DDL runs inside
 * its own {@code afterPropertiesSet()} during singleton instantiation, strictly before
 * {@code SmartInitializingSingleton} callbacks fire. So this check is guaranteed to run after every
 * Liquibase master present has already applied, with no explicit {@code @DependsOn} — the ordering
 * falls out of the Spring Boot lifecycle contract itself, not out of a string naming a bean. That
 * guarantee holds only as long as nothing in the chain becomes {@code @Lazy}; a lazy Liquibase bean
 * would defer its DDL past this check silently.
 *
 * <p>No-ops when no {@code DataSource} bean is present, the shape every
 * {@code ApplicationContextRunner} test in this codebase uses, so this class never forces a
 * container onto a context that was never asking for a live database.
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
     * The event-time column migration {@code 0012} added to every open detection table, and which a
     * paid overlay's own tables must carry in the same release ({@code AGENTS.md}'s paired-PR rule
     * for that migration) — checked here rather than left for the writer's insert to fail on at the
     * first detection, which would surface as a classifier silently never writing anything.
     */
    private static final String SUBJECT_STARTED_AT = "subject_started_at";

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
                        + "' names table '" + table.table() + "', which has no '" + column
                        + "' column — its owning module's Liquibase changelog is behind migration 0012");
            }
        }
    }
}
