// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The lookup that finds which adapter owns a finding. The bugs are an open edition that claims a finding, a lookup
 * that answers with the first adapter whatever it owns, and two owners resolved by classpath order.
 */
class FindingAdaptersTest {

    private static FindingClaim claim(String classifierKey) {
        return FindingRowBuilder.of(classifierKey).id("f1").build().claim();
    }

    /** The open edition ships no adapter: no finding is owned and none has an adapter's method. */
    @Test
    void noAdapterOwnsNothing() {
        FindingAdapters adapters = FindingAdapters.of(List.of());

        assertTrue(adapters.isEmpty());
        assertEquals(Optional.empty(), adapters.ownerOf(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.method(claim("custom-x")));
    }

    /** The owner is the adapter whose {@code owns} says yes, not the first one registered. */
    @Test
    void theOwnerIsTheAdapterThatClaimsTheKey() {
        FakeFindingAdapter a = FakeFindingAdapter.owning("custom-a", "# Method a\n");
        FakeFindingAdapter x = FakeFindingAdapter.owning("custom-x", "# Method x\n");
        FindingAdapters adapters = FindingAdapters.of(List.of(a, x));

        assertSame(x, adapters.ownerOf(claim("custom-x")).orElseThrow());
        assertEquals(Optional.of("# Method x\n"), adapters.method(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.ownerOf(claim("custom-b")));
    }

    /** Two adapters claiming one finding fail loud, naming both, rather than letting classpath order pick. */
    @Test
    void twoOwnersFailLoud() {
        FindingAdapters adapters = FindingAdapters.of(
                List.of(FakeFindingAdapter.owning("custom-x", "# One\n"), new SecondOwner("custom-x")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> adapters.ownerOf(claim("custom-x")));

        assertEquals(
                "finding f1 is owned by both " + FakeFindingAdapter.class.getName() + " and "
                        + SecondOwner.class.getName(),
                e.getMessage());
    }

    /** A second adapter class, so the failure message can be checked for both names. */
    private static final class SecondOwner implements FindingAdapter {
        private final FakeFindingAdapter delegate;

        SecondOwner(String key) {
            this.delegate = FakeFindingAdapter.owning(key, "# Two\n");
        }

        @Override
        public boolean owns(FindingClaim finding) {
            return delegate.owns(finding);
        }

        @Override
        public Optional<String> method(FindingClaim finding) {
            return delegate.method(finding);
        }

        @Override
        public void absorbed(FindingRow finding, @Nullable String userId, String now) {
            delegate.absorbed(finding, userId, now);
        }
    }
}
