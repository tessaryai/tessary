// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformStaffPropertiesTest {

    /**
     * The bugs: a staff address configured with stray case or whitespace never matches the login email;
     * a null or blank login email (a provider that returned none) matches a blank entry in the list and
     * is granted staff access.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {
                "ops@tessary.ai, true",
                "' OPS@Tessary.AI ', true",
                "dev@tessary.ai, false",
                "NULL, false",
                "'  ', false"
            })
    void matchesConfiguredStaffIgnoringCaseAndWhitespace(@Nullable String email, boolean expected) {
        PlatformStaffProperties p = new PlatformStaffProperties();
        p.setStaffEmails(Arrays.asList(" Ops@Tessary.ai ", null, "  "));

        assertEquals(expected, p.isStaffEmail(email));
    }
}
