// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.config.PlatformStaffProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Staff standing belongs to a signed-in human on the allow-list. A bearer key minted by that same
 * person acts for a project, not for them, so it never carries staff powers.
 */
class PlatformStaffTest {

    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"ops@tessary.ai, NULL, true", "someone@example.com, NULL, false", "ops@tessary.ai, tok_1, false"})
    void onlyAnAllowListedHumanSessionIsStaff(String email, @Nullable String mcpTokenId, boolean staff) {
        PlatformStaffProperties props = new PlatformStaffProperties();
        props.setStaffEmails(List.of("ops@tessary.ai"));

        assertEquals(
                staff,
                new PlatformStaff(props).isStaff(new TenantContext("usr_1", email, null, null, null, mcpTokenId)));
    }
}
