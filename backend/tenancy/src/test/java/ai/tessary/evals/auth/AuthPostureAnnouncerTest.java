// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the #996 fix: {@code evals.auth.disabled} alone decides which posture this announces,
 * regardless of {@link AuthProvider#isEnabled()} -- crew review caught this class still branching
 * on the retired "configured provider always wins" precedence, so it kept announcing "enforced"
 * even while {@link AuthFilter} was actually bypassing every request. No test existed for this
 * class before that finding.
 */
class AuthPostureAnnouncerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(AuthPostureAnnouncer.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    private static AuthProperties disabled(boolean disabled) {
        AuthProperties props = new AuthProperties();
        props.setDisabled(disabled);
        return props;
    }

    @Test
    void disabledFlagWarnsEvenWithAnEnabledProviderConfigured() {
        // The exact scenario the flag now applies to (#852/#996): a real, "enabled" provider is
        // present, but the operator's own flag says bypass everything. Before the fix, this cell
        // logged "enforced" -- the opposite of what AuthFilter actually did.
        AuthProvider enabledProvider = mock(AuthProvider.class);
        when(enabledProvider.isEnabled()).thenReturn(true);

        new AuthPostureAnnouncer(enabledProvider, disabled(true)).announce();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("AUTH IS DISABLED"));
    }

    @Test
    void enabledProviderWithNoOptOutEnforcesQuietly() {
        AuthProvider enabledProvider = mock(AuthProvider.class);
        when(enabledProvider.isEnabled()).thenReturn(true);

        new AuthPostureAnnouncer(enabledProvider, disabled(false)).announce();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("enforced"));
    }

    @Test
    void disabledProviderWithNoOptInWarnsUnconfigured() {
        AuthProvider disabledProvider = mock(AuthProvider.class);
        when(disabledProvider.isEnabled()).thenReturn(false);

        new AuthPostureAnnouncer(disabledProvider, disabled(false)).announce();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("No identity provider is configured"));
    }

    @Test
    void disabledProviderWithOptInWarnsAuthDisabled() {
        AuthProvider disabledProvider = mock(AuthProvider.class);
        when(disabledProvider.isEnabled()).thenReturn(false);

        new AuthPostureAnnouncer(disabledProvider, disabled(true)).announce();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("AUTH IS DISABLED"));
    }
}
