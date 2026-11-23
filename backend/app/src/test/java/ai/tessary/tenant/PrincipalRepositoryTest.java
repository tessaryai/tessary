// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
record PrincipalRepositoryTest(PrincipalRepository principals) {

    /**
     * The bugs: the batched author lookup behind the prompt version list drops or mis-maps a column, reads a principal
     * that was not asked for, or fails on an id that names no principal.
     */
    @Test
    void findByIds_roundTripsEveryColumnOfTheAskedPrincipalsOnly() {
        long n = System.nanoTime();
        Principal human = new Principal(
                "p-human-" + n,
                "user_fbi_" + n,
                "fbi+" + n + "@example.com",
                "Ana Lima",
                "https://example.com/a.png",
                Principal.Kind.HUMAN,
                null,
                "{\"team\": \"core\"}",
                "2026-10-01T00:00:00Z",
                "2026-10-02T00:00:00Z");
        Principal agent = new Principal(
                "p-agent-" + n,
                null,
                null,
                null,
                null,
                Principal.Kind.AGENT,
                human.id(),
                "{}",
                "2026-10-03T00:00:00Z",
                null);
        Principal other = Principal.human(
                "p-other-" + n, "user_fbo_" + n, "fbo+" + n + "@example.com", "Bo", null, "2026-10-04T00:00:00Z", null);
        principals.insert(human);
        principals.insert(agent);
        principals.insert(other);

        assertEquals(
                Set.of(human, agent), Set.copyOf(principals.findByIds(Set.of(human.id(), agent.id(), "p-gone-" + n))));
        assertEquals(List.of(), principals.findByIds(Set.of()));
    }
}
