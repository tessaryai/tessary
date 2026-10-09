// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertPropertiesTest {

    /**
     * The bug: a {@code tessary.alert.*} override binds to the wrong field or is silently ignored, so
     * digests fire at the default hour or links in alerts point at the wrong host.
     */
    @Test
    void everyKeyBindsToItsOwnField() {
        AlertProperties p = ConfigBinding.bind(
                "tessary.alert",
                new AlertProperties(),
                Map.of(
                        "tessary.alert.default-digest-cron", "0 30 9 * * MON",
                        "tessary.alert.cron-zone", "Asia/Kolkata",
                        "tessary.alert.app-base-url", "https://app.example"));

        assertEquals(
                List.of("0 30 9 * * MON", "Asia/Kolkata", "https://app.example"),
                List.of(p.getDefaultDigestCron(), p.getCronZone(), p.getAppBaseUrl()));
    }
}
