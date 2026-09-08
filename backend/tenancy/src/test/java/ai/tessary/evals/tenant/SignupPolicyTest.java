// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignupPolicyTest {

    @Test
    void absentOrUnreadableSettingsMeanOpen() {
        assertEquals(SignupPolicy.OPEN, SignupPolicy.fromSettings(null));
        assertEquals(SignupPolicy.OPEN, SignupPolicy.fromSettings("{}"));
        assertEquals(SignupPolicy.OPEN, SignupPolicy.fromSettings("not json"));
        assertEquals(SignupPolicy.OPEN, SignupPolicy.fromSettings("{\"signupPolicy\":{\"mode\":\"bogus\"}}"));
    }

    @Test
    void domainsAreNormalisedAndValidated() {
        SignupPolicy p = SignupPolicy.of("domain", Arrays.asList(" @Acme-Corp.com ", "acme.io", "acme.io", null, ""));
        assertEquals(List.of("acme-corp.com", "acme.io"), p.domains());
        assertTrue(p.admitsDomainOf("Someone@ACME-CORP.com"));
        assertFalse(p.admitsDomainOf("someone@sub.acme-corp.com"));
        assertFalse(p.admitsDomainOf("no-at-sign"));
        assertThrows(IllegalArgumentException.class, () -> SignupPolicy.of("domain", List.of()));
        assertThrows(IllegalArgumentException.class, () -> SignupPolicy.of("domain", List.of("acme corp.com")));
        assertThrows(IllegalArgumentException.class, () -> SignupPolicy.of("locked", List.of()));
        assertThrows(IllegalArgumentException.class, () -> SignupPolicy.of(null, List.of()));
    }

    @Test
    void changesPolicyDetectsOnlyAPolicyChange() {
        String stored = SignupPolicy.of("invite", null).intoSettings("{\"theme\":\"dark\"}");
        assertFalse(SignupPolicy.changesPolicy(stored, null));
        assertTrue(
                SignupPolicy.changesPolicy(stored, "{\"theme\":\"light\"}"), "omitting a stored policy would drop it");
        assertFalse(SignupPolicy.changesPolicy(null, "{\"theme\":\"light\"}"));
        assertTrue(SignupPolicy.omitsPolicy("{\"theme\":\"light\"}"));
        assertFalse(SignupPolicy.omitsPolicy(stored));
        assertEquals(
                SignupPolicy.Mode.INVITE,
                SignupPolicy.fromSettings(SignupPolicy.carryInto(stored, "{\"theme\":\"light\"}"))
                        .mode());
        assertEquals(
                "{\"theme\":\"light\"}",
                SignupPolicy.carryInto("{\"theme\":\"dark\"}", "{\"theme\":\"light\"}"),
                "an org that never set a policy stays that way");
        assertFalse(SignupPolicy.changesPolicy(stored, stored));
        assertTrue(SignupPolicy.changesPolicy(stored, SignupPolicy.OPEN.intoSettings(null)));
        assertTrue(SignupPolicy.changesPolicy(null, stored));
        assertTrue(SignupPolicy.changesPolicy(stored, "not json"));
    }

    @Test
    void settingsRoundTripKeepsOtherKeys() throws Exception {
        String settings = SignupPolicy.of("invite", null).intoSettings("{\"theme\":\"dark\"}");
        JsonNode root = new ObjectMapper().readTree(settings);
        assertEquals("dark", root.path("theme").asText());
        assertEquals("invite", root.path("signupPolicy").path("mode").asText());
        assertEquals(
                SignupPolicy.Mode.INVITE, SignupPolicy.fromSettings(settings).mode());

        String reopened = SignupPolicy.OPEN.intoSettings(settings);
        assertEquals("dark", new ObjectMapper().readTree(reopened).path("theme").asText());
        assertEquals(SignupPolicy.OPEN, SignupPolicy.fromSettings(reopened));
    }
}
