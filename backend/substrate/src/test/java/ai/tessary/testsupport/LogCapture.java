// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(Class<?> source) {
        this.logger = (Logger) LoggerFactory.getLogger(source);
    }

    public static LogCapture of(Class<?> source) {
        return new LogCapture(source);
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
}
