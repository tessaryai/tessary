// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.testsupport.Props;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlackPropertiesTest {

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
        SlackProperties p = Props.of(SlackProperties.class, "base-url", baseUrl, "service-key", serviceKey);

        assertEquals(expected, p.isConfigured());
    }
}
