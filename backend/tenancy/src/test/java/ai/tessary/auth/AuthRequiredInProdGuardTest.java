// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A production boot with no cookie password would issue sessions nobody can unseal: it must not start. */
class AuthRequiredInProdGuardTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @SuppressWarnings("NullAway") // deliberate: an unbound property is what the guard exists to catch
    void refusesToStartWithoutACookiePassword(String password) {
        AuthProperties props = new AuthProperties();
        props.setCookiePassword(password);

        assertThrows(IllegalStateException.class, () -> new AuthRequiredInProdGuard(props).verify());
    }

    @Test
    void startsWithOne() {
        AuthProperties props = new AuthProperties();
        props.setCookiePassword(Base64.getEncoder().encodeToString(new byte[32]));

        assertDoesNotThrow(() -> new AuthRequiredInProdGuard(props).verify());
    }
}
