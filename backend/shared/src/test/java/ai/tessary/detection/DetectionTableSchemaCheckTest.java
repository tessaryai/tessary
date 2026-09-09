// SPDX-License-Identifier: Apache-2.0
package ai.tessary.detection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.detection.DetectionTable.Grain;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * No {@code ApplicationContextRunner} here — {@code backend/shared} carries no Spring Boot test
 * harness dependency (only plain {@code junit-jupiter}) and none is needed:
 * {@link DetectionTableSchemaCheck} is a plain object with an {@code ObjectProvider<DataSource>}
 * constructor argument, exercised directly. Real JDBC objects are stood in with
 * {@link Proxy} rather than a mocking library, since none is on this module's test classpath either.
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
    @SuppressWarnings("unchecked")
    private static DataSource fakeDataSource(boolean tableExists) {
        InvocationHandler resultSetHandler = (proxy, method, args) -> switch (method.getName()) {
            case "next" -> true;
            case "getBoolean" -> tableExists;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                DetectionTableSchemaCheckTest.class.getClassLoader(),
                new Class<?>[] {ResultSet.class},
                resultSetHandler);

        InvocationHandler statementHandler = (proxy, method, args) -> switch (method.getName()) {
            case "executeQuery" -> rs;
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

    @Test
    void presentTableStartsClean() {
        DetectionTableRegistry registry = new DetectionTableRegistry(
                tablesOf(new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN)));
        DetectionTableSchemaCheck check = new DetectionTableSchemaCheck(registry, providerOf(fakeDataSource(true)));

        assertDoesNotThrow(check::afterSingletonsInstantiated);
    }
}
