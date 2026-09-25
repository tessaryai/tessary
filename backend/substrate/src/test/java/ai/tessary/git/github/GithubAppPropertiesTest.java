// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What counts as a configured App and as usable OAuth credentials. A value can be absent as well as blank:
 * {@link GithubAppConfigService} applies a captured row whose older JSON may lack a field, which reads back
 * as null, and that must read as "not configured" rather than throw.
 */
class GithubAppPropertiesTest {

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"123, pem, true", "NULL, pem, false", "' ', pem, false", "123, NULL, false", "123, ' ', false"})
    void isConfigured_needsBothTheAppIdAndItsKey(String appId, String pem, boolean configured) {
        GithubAppProperties props = new GithubAppProperties();
        props.applyAll(appId, pem, "slug", "cid", "secret");
        assertEquals(configured, props.isConfigured());
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "cid, secret, true",
                "NULL, secret, false",
                "' ', secret, false",
                "cid, NULL, false",
                "cid, ' ', false"
            })
    void hasOAuth_needsBothTheClientIdAndSecret(String clientId, String clientSecret, boolean oauth) {
        GithubAppProperties props = new GithubAppProperties();
        props.applyAll("123", "pem", "slug", clientId, clientSecret);
        assertEquals(oauth, props.hasOAuth());
    }
}
