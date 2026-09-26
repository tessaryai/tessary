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
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code backend/shared} has no Spring Boot test harness or mocking library, so {@link DetectionTableSchemaCheck} is
 * exercised directly with JDBC objects stood in by {@link Proxy}.
 */
class DetectionTableSchemaCheckTest {

    private static <T> ObjectProvider<T> providerOf(T item) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return item;
            }

            @Override
            public T getIfAvailable() {
                return item;
            }

            @Override
            public Stream<T> orderedStream() {
                return item == null ? Stream.empty() : Stream.of(item);
            }
        };
    }

    private static ObjectProvider<DataSource> noDataSource() {
        return providerOf(null);
    }

    /** A {@link DataSource} whose {@code to_regclass(...)} answer is fixed for every query it runs. */
    private static DataSource fakeDataSource(boolean tableExists) {
        return fakeDataSource(tableExists, tableExists);
    }

    /**
     * Answers the table-existence query with {@code tableExists} and the {@code subject_started_at} probe (SQL naming
     * {@code information_schema}) with {@code columnExists}.
     */
    @SuppressWarnings("unchecked")
    private static DataSource fakeDataSource(boolean tableExists, boolean columnExists) {
        InvocationHandler statementHandler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" ->
                resultSetAnswering(((String) args[0]).contains("information_schema") ? columnExists : tableExists);
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

    private static ObjectProvider<DetectionTable> tablesOf(DetectionTable... items) {
        return new ObjectProvider<>() {
            @Override
            public DetectionTable getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Stream<DetectionTable> orderedStream() {
                return Stream.of(items);
            }
        };
    }

    @Test
    void noDataSourceBeanStartsClean() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                tablesOf(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, noDataSource());

        assertDoesNotThrow(check::afterSingletonsInstantiated);
    }

    @Test
    void missingTableFailsBootNamingKindAndTable() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                tablesOf(new DetectionTable("frustration", "frustration_detection", Grain.TRACE)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, providerOf(fakeDataSource(false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("frustration"));
        assertTrue(ex.getMessage().contains("frustration_detection"));
    }

    /** A table registered before its changelog carries migration {@code 0012}: the paid-overlay paired-PR case. */
    @Test
    void missingSubjectStartedAtColumnFailsBootNamingKindAndTable() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                tablesOf(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN)));
        DetectionTableSchemaCheck check =
                new DetectionTableSchemaCheck(registry, providerOf(fakeDataSource(true, false)));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertTrue(ex.getMessage().contains("secret_leak"));
        assertTrue(ex.getMessage().contains("secret_leak_detection"));
        assertTrue(ex.getMessage().contains("subject_started_at"));
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
                tablesOf(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, providerOf(down));

        IllegalStateException ex = assertThrows(IllegalStateException.class, check::afterSingletonsInstantiated);
        assertEquals(
                "failed to verify detection table 'secret_leak_detection' for kind 'secret_leak' exists",
                ex.getMessage());
        assertSame(refused, ex.getCause());
    }
}
