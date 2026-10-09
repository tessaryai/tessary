// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlackPropertiesTest {

    /** The bug: a {@code tessary.slack.*} override binds to the wrong field or is silently ignored. */
    @Test
    void everyKeyBindsToItsOwnField() {
        SlackProperties p = ConfigBinding.bind(
                "tessary.slack",
                new SlackProperties(),
                Map.of(
                        "tessary.slack.base-url", "https://slack-bridge.example",
                        "tessary.slack.service-key", "svc",
                        "tessary.slack.timeout-seconds", "7"));

        assertEquals("https://slack-bridge.example", p.getBaseUrl());
        assertEquals("svc", p.getServiceKey());
        assertEquals(7L, p.getTimeoutSeconds());
    }

    /**
     * The bug: Slack delivery is offered (and then fails on every send) when either the bridge URL or its
     * key is missing or blank; it is configured only when both are set.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "https://b, svc, true",
                "NULL, svc, false",
                "'  ', svc, false",
                "https://b, NULL, false",
                "https://b, '  ', false"
            })
    void configuredOnlyWhenBothUrlAndKeyAreSet(
            @Nullable String baseUrl, @Nullable String serviceKey, boolean expected) {
        SlackProperties p = new SlackProperties();
        p.setBaseUrl(baseUrl);
        p.setServiceKey(serviceKey);

        assertEquals(expected, p.isConfigured());
    }
}
