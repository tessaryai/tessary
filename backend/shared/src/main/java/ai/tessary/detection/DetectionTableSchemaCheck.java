// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
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

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

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
     * Every registered table name is a compile-time constant today, so this cannot fire. It is here
     * because the name below is concatenated into SQL rather than bound, which makes "registered in
     * code" a property a future registrant has to keep true rather than one the type system holds.
     * Same guard, same reason, as {@code ProjectPurgeRepository.assertKnown}, which is the other
     * place in this codebase that interpolates an identifier.
     */
    private static void assertPlainIdentifier(String table) {
        if (!PLAIN_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("not a plain table identifier: " + table);
        }
    }

    private void assertExists(DataSource ds, DetectionTable table) {
        assertPlainIdentifier(table.table());
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT to_regclass('" + table.table() + "') IS NOT NULL");
            if (!rs.next() || !rs.getBoolean(1)) {
                throw new IllegalStateException(
                        "DetectionTable registered for kind '" + table.detectorKind() + "' names table '"
                                + table.table()
                                + "', which does not exist in the connected database — its owning module's"
                                + " Liquibase changelog must run before this check does");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "failed to verify detection table '" + table.table() + "' for kind '" + table.detectorKind()
                            + "' exists",
                    e);
        }
    }
}
