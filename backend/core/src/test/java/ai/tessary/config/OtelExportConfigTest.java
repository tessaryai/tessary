// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link OtelExportConfig}: the production profile's installer hands the OpenTelemetry SDK to the
 * logback OTEL appender. The SDK here is a fake whose logs bridge records each emitted body.
 */
class OtelExportConfigTest {

    /**
     * The bug: the appender declared in {@code logback-spring.xml} never receives an OpenTelemetry
     * instance, so it buffers its first events and then drops every log line, and Loki stays empty.
     */
    @Test
    void theInstallerConnectsTheOtelAppenderToTheSdk() {
        List<String> exported = new ArrayList<>();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        OpenTelemetryAppender appender = new OpenTelemetryAppender();
        appender.setContext(context);
        appender.setName("otel-export-config-test");
        appender.start();
        Logger logger = context.getLogger("ai.tessary.config.OtelExportConfigTest.probe");
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            new OtelExportConfig().openTelemetryAppenderInstaller(recordingSdk(exported));

            logger.warn("pool saturated");

            assertEquals(List.of("pool saturated"), exported);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** An OpenTelemetry whose logs bridge answers every builder call with itself and records each body. */
    private static OpenTelemetry recordingSdk(List<String> bodies) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                if (method.getName().equals("setBody") && args.length == 1) {
                    bodies.add(args[0] instanceof Value<?> v ? String.valueOf(v.getValue()) : String.valueOf(args[0]));
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return true;
                }
                if (type.isInterface() && type.getName().startsWith("io.opentelemetry.api.")) {
                    return Proxy.newProxyInstance(
                            OtelExportConfigTest.class.getClassLoader(), new Class<?>[] {type}, this);
                }
                return null;
            }
        };
        LoggerProvider logs = (LoggerProvider) Proxy.newProxyInstance(
                OtelExportConfigTest.class.getClassLoader(), new Class<?>[] {LoggerProvider.class}, handler);
        return new OpenTelemetry() {
            @Override
            public TracerProvider getTracerProvider() {
                return TracerProvider.noop();
            }

            @Override
            public ContextPropagators getPropagators() {
                return ContextPropagators.noop();
            }

            @Override
            public LoggerProvider getLogsBridge() {
                return logs;
            }
        };
    }
}
