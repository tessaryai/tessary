// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.detection.DetectionTable.Grain;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * {@code backend/shared} has no Spring Boot test harness or mocking library, so {@link DetectionTableSchemaCheck} is
 * exercised directly with JDBC objects stood in by {@link Proxy}.
 */
class DetectionTableSchemaCheckTest {

    /** A {@link DataSource} whose {@code to_regclass(...)} answer is fixed for every query it runs. */
    private static DataSource fakeDataSource(boolean tableExists) {
        return fakeDataSource(tableExists, tableExists);
    }

    /**
     * Answers the table-existence query with {@code tableExists} and the {@code subject_started_at} probe (SQL naming
     * {@code information_schema}) with {@code columnExists}.
     */
    private static DataSource fakeDataSource(boolean tableExists, boolean columnExists) {
        return fakeDataSource(tableExists, columnExists, columnExists);
    }

    /**
     * As above, with the {@code cleared_at} probe (the column query naming {@code cleared_at}) answered by
     * {@code clearedAtExists}.
     */
    @SuppressWarnings("unchecked")
    private static DataSource fakeDataSource(boolean tableExists, boolean columnExists, boolean clearedAtExists) {
        InvocationHandler statementHandler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> {
                String sql = (String) args[0];
                if (!sql.contains("information_schema")) yield resultSetAnswering(tableExists);
                yield resultSetAnswering(sql.contains("'cleared_at'") ? clearedAtExists : columnExists);
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        Statement stmt = (Statement) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                statementHandler);

        InvocationHandler connectionHandler = (proxy, method, args) -> switch (method.getName()) {
            case "createStatement" -> stmt;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        Connection conn = (Connection) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                connectionHandler);

        InvocationHandler dsHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getConnection" -> conn;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (DataSource) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(), new Class<?>[] {DataSource.class}, dsHandler);
    }

    private static ResultSet resultSetAnswering(boolean answer) {
        InvocationHandler resultSetHandler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> true;
            case "getBoolean" -> answer;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (ResultSet) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                resultSetHandler);
    }

    @Test
    void noDataSourceBeanStartsClean() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, Providers.<DataSource>of());

        assertDoesNotThrow(check::afterSingletonsInstantiated);
    }

    @Test
    void missingTableFailsBootNamingKindAndTable() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("frustration", "frustration_detection", Grain.TRACE, false, false)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, Providers.of(fakeDataSource(false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("frustration"));
        assertTrue(ex.getMessage().contains("frustration_detection"));
    }

    /** A table registered before its changelog carries migration {@code 0012}: the paid-overlay paired-PR case. */
    @Test
    void missingSubjectStartedAtColumnFailsBootNamingKindAndTable() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        DetectionTableSchemaCheck check =
                new DetectionTableSchemaCheck(registry, Providers.of(fakeDataSource(true, false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("secret_leak"));
        assertTrue(ex.getMessage().contains("secret_leak_detection"));
        assertTrue(ex.getMessage().contains("subject_started_at"));
    }

    /**
     * A table registered clearable whose changelog has no {@code cleared_at} refuses boot naming the kind, the table
     * and the column. The bug is a trace view that filters on a column the table lacks and fails on the first read.
     */
    @Test
    void aClearableTableNeedsItsClearedAtColumn() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("frustration", "frustration_detection", Grain.TRACE, true, true)));
        DetectionTableSchemaCheck check =
                new DetectionTableSchemaCheck(registry, Providers.of(fakeDataSource(true, true, false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);

        assertEquals(
                "DetectionTable registered for kind 'frustration' names table 'frustration_detection', which has no"
                        + " 'cleared_at' column — it is registered clearable, so its owning module's changelog must add"
                        + " the column",
                ex.getMessage());
    }

    /** A table not registered clearable is not asked for {@code cleared_at}. */
    @Test
    void aTableNotClearableBootsWithoutClearedAt() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        DetectionTableSchemaCheck check =
                new DetectionTableSchemaCheck(registry, Providers.of(fakeDataSource(true, true, false)));

        assertDoesNotThrow(check::afterSingletonsInstantiated);
    }

    /** A refused connection at boot names the table and kind, keeping the driver's exception as its cause. */
    @Test
    void unreachableDatabaseFailsBootNamingTheTableWithTheDriverCause() {
        SQLException refused = new SQLException("connection refused");
        DataSource down = (DataSource) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    if (m.getName().equals("getConnection")) throw refused;
                    throw new UnsupportedOperationException(m.getName());
                });
        DetectionTableRegistry registry = new DetectionTableRegistry(
                Providers.of(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, Providers.of(down));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertEquals(
                "failed to verify detection table 'secret_leak_detection' for kind 'secret_leak' exists",
                ex.getMessage());
        assertSame(refused, ex.getCause());
    }
}
