// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.RcaProperties;
import ai.tessary.config.TessaryProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the boundary that makes the shipped placeholder keys defensible: permitted on a
 * domainless instance, fatal on one that has a hostname.
 *
 * <p>The refuse branch is otherwise reachable only through {@code check-open-boot-selfhost.sh},
 * which is EXCLUDED from {@code task check} and needs Docker, so nothing an engineer runs
 * per-change would exercise it.
 */
class PlaceholderSecretGuardTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        logbackLogger = (Logger) LoggerFactory.getLogger(PlaceholderSecretGuard.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
    }

    private static PlaceholderSecretGuard guard(String siteDomain, String secretKey, String cookiePassword) {
        return guard(siteDomain, secretKey, cookiePassword, REAL_KEY);
    }

    private static PlaceholderSecretGuard guard(
            String siteDomain, String secretKey, String cookiePassword, String launcherApiKey) {
        TessaryProperties tessary = new TessaryProperties();
        tessary.setSiteDomain(siteDomain);
        tessary.setSecretKey(secretKey);
        AuthProperties auth = new AuthProperties();
        auth.setCookiePassword(cookiePassword);
        RcaProperties rca = new RcaProperties();
        rca.getAgentic().setLauncherApiKey(launcherApiKey);
        return new PlaceholderSecretGuard(tessary, auth, rca);
    }

    private static final String REAL_KEY = "b6jUqcQBRDIrJt5lZ0nCkQCLB1kIzHXKUuqZM1tEPCM=";

    private static String refusalOf(PlaceholderSecretGuard g) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, g::verify);
        return Objects.requireNonNull(thrown.getMessage(), "the refusal has to tell the operator what to fix");
    }

    @Test
    void verify_refusesTheBootWhenADomainMeetsThePlaceholderCookieKey() {
        String message = refusalOf(guard("app.example.com", REAL_KEY, PlaceholderSecretGuard.COOKIE_PLACEHOLDER));

        assertTrue(
                message.contains("TESSARY_AUTH_COOKIE_PASSWORD"),
                "the message must name the offending key so the operator knows which to replace");
        assertTrue(
                !message.contains("TESSARY_SECRET_KEY"),
                "the sealing key was replaced, so naming it would send the operator after the wrong one");
        assertTrue(message.contains("openssl rand -base64 32"), "the message must carry the fix");
    }

    @Test
    void verify_refusesTheBootWhenADomainMeetsThePlaceholderSealingKey() {
        String message = refusalOf(guard("app.example.com", PlaceholderSecretGuard.SEALING_PLACEHOLDER, REAL_KEY));

        assertTrue(message.contains("TESSARY_SECRET_KEY"));
        assertTrue(!message.contains("TESSARY_AUTH_COOKIE_PASSWORD"));
    }

    @Test
    void verify_namesBothKeysWhenBothAreStillDefault() {
        String message = refusalOf(guard(
                "app.example.com",
                PlaceholderSecretGuard.SEALING_PLACEHOLDER,
                PlaceholderSecretGuard.COOKIE_PLACEHOLDER));

        assertTrue(message.contains("TESSARY_AUTH_COOKIE_PASSWORD"));
        assertTrue(message.contains("TESSARY_SECRET_KEY"));
    }

    @Test
    void verify_bootsWithAWarningOnALocalhostInstance() {
        // The whole point of the placeholders: `docker compose up -d` with no .env has to work.
        guard("", PlaceholderSecretGuard.SEALING_PLACEHOLDER, PlaceholderSecretGuard.COOKIE_PLACEHOLDER)
                .verify();

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("TESSARY_AUTH_COOKIE_PASSWORD"));
        assertTrue(event.getFormattedMessage().contains("TESSARY_SECRET_KEY"));
    }

    @Test
    void verify_readsCaddysInertLoopbackLiteralAsNoDomain() {
        // The static Caddyfile's old sentinel is not a listener and nothing passes it any
        // more, but a .env that still carries the literal must not refuse the boot.
        guard("http://127.0.0.1:9443", REAL_KEY, PlaceholderSecretGuard.COOKIE_PLACEHOLDER)
                .verify();

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
    }

    @Test
    void verify_saysNothingWhenBothKeysHaveBeenReplaced() {
        guard("app.example.com", REAL_KEY, REAL_KEY).verify();

        assertTrue(appender.list.isEmpty(), "a properly configured instance must boot silently");
    }

    @Test
    void theLauncherPlaceholderRefusesADomainedBootLikeTheOtherTwo() {
        // It earns the same treatment: anyone holding this published value can drive the sandbox
        // launcher into spawning containers on the host. It became a placeholder only because the
        // previous empty default meant the launcher 401'd every run out of the box.
        PlaceholderSecretGuard g =
                guard("tessary.acme-corp.com", REAL_KEY, REAL_KEY, PlaceholderSecretGuard.LAUNCHER_PLACEHOLDER);
        assertTrue(
                refusalOf(g).contains("TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY"),
                "the refusal has to name the variable to change");
    }

    @Test
    void theLauncherPlaceholderOnLocalhostWarnsRatherThanRefusing() {
        guard("", REAL_KEY, REAL_KEY, PlaceholderSecretGuard.LAUNCHER_PLACEHOLDER)
                .verify();
        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> String.valueOf(e.getFormattedMessage())
                                .contains("TESSARY_OBSERVER_AGENTIC_LAUNCHER_API_KEY")),
                "running on a published launcher secret must never be invisible, even on localhost");
    }
}
