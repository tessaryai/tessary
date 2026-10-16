// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.testsupport.LogCapture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code tessary.auth.disabled} alone decides which posture this announces,
 * regardless of which {@link AuthProvider} is configured -- crew review caught this class still branching
 * on the retired "configured provider always wins" precedence, so it kept announcing "enforced"
 * even while {@link AuthFilter} was actually bypassing every request. No test existed for this
 * class before that finding.
 */
class AuthPostureAnnouncerTest {

    @RegisterExtension
    final LogCapture log = LogCapture.of(AuthPostureAnnouncer.class);

    private static AuthProperties disabled(boolean disabled) {
        AuthProperties props = new AuthProperties();
        props.setDisabled(disabled);
        return props;
    }

    @Test
    void disabledFlagWarnsEvenWithAnEnabledProviderConfigured() {
        // The exact scenario the flag applies to: a real provider is
        // present, but the operator's own flag says bypass everything. Before the fix, this cell
        // logged "enforced" -- the opposite of what AuthFilter actually did.
        new AuthPostureAnnouncer(mock(AuthProvider.class), disabled(true)).announce();

        assertEquals(1, log.events().size());
        ILoggingEvent event = log.events().get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("AUTH IS DISABLED"));
    }
}
