// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/** What one class's logger writes: for a whole test as an extension, or while one action runs. */
public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> source) {
        this.logger = (Logger) LoggerFactory.getLogger(source);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source);
    }

    public static LogCapture during(Class<?> source, Runnable action) {
        LogCapture capture = of(source);
        capture.beforeEach(null);
        try {
            action.run();
        } finally {
            capture.afterEach(null);
        }
        return capture;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        appender.start();
        logger.addAppender(appender);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        logger.detachAppender(appender);
    }

    public List<ILoggingEvent> events() {
        return appender.list;
    }

    public ILoggingEvent first(Level level) {
        return events().stream().filter(e -> e.getLevel() == level).findFirst().orElseThrow();
    }

    public long count(Level level) {
        return events().stream().filter(e -> e.getLevel() == level).count();
    }

    /** The structured fields of every captured event whose {@code event} field is {@code name}. */
    public List<Map<String, Object>> fields(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ILoggingEvent e : events()) {
            if (e.getKeyValuePairs() == null) continue;
            Map<String, Object> fields = new HashMap<>();
            e.getKeyValuePairs().forEach(kv -> fields.put(kv.key, kv.value));
            if (name.equals(fields.get("event"))) out.add(fields);
        }
        return out;
    }
}
