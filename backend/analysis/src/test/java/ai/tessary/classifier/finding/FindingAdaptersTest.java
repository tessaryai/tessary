// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.finding.dossier.ClassifierDossierAssembler.EvidenceCounts;
import ai.tessary.classifier.finding.dossier.EvidenceContext;
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

    private static final EvidenceContext CONTEXT =
            new EvidenceContext(new EvidenceCounts(0, 0, 0, 0, 0), "## Evidence enumeration\n");

    /** The open edition ships no adapter: no finding is owned and none has an adapter's dossier file. */
    @Test
    void noAdapterOwnsNothing() {
        FindingAdapters adapters = FindingAdapters.of(List.of());

        assertTrue(adapters.isEmpty());
        assertEquals(Optional.empty(), adapters.ownerOf(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.triageMethod(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.rcaMethod(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.evidence(claim("custom-x"), CONTEXT));
    }

    /**
     * Each lookup hands back its own file. The bug is one text served as both methods, so the RCA agent reads the
     * triage method or the reverse.
     */
    @Test
    void theRcaMethodIsNotTheTriageMethod() {
        FindingAdapters adapters = FindingAdapters.of(
                List.of(FakeFindingAdapter.owning("owned-x", "# Triage method\n", "# RCA method\n", "# Evidence\n")));

        assertEquals(Optional.of("# Triage method\n"), adapters.triageMethod(claim("owned-x")));
        assertEquals(Optional.of("# RCA method\n"), adapters.rcaMethod(claim("owned-x")));
        assertEquals(Optional.of("# Evidence\n"), adapters.evidence(claim("owned-x"), CONTEXT));
    }

    /** The owner is the adapter whose {@code owns} says yes, not the first one registered. */
    @Test
    void theOwnerIsTheAdapterThatClaimsTheKey() {
        FakeFindingAdapter a = FakeFindingAdapter.owning("custom-a", "# Method a\n", "# Method a\n", null);
        FakeFindingAdapter x = FakeFindingAdapter.owning("custom-x", "# Method x\n", "# Method x\n", null);
        FindingAdapters adapters = FindingAdapters.of(List.of(a, x));

        assertSame(x, adapters.ownerOf(claim("custom-x")).orElseThrow());
        assertEquals(Optional.of("# Method x\n"), adapters.triageMethod(claim("custom-x")));
        assertEquals(Optional.empty(), adapters.ownerOf(claim("custom-b")));
    }

    /** Two adapters claiming one finding fail loud, naming both, rather than letting classpath order pick. */
    @Test
    void twoOwnersFailLoud() {
        FindingAdapters adapters = FindingAdapters.of(List.of(
                FakeFindingAdapter.owning("custom-x", "# One\n", "# One\n", null), new SecondOwner("custom-x")));

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
            this.delegate = FakeFindingAdapter.owning(key, "# Two\n", "# Two\n", null);
        }

        @Override
        public boolean owns(FindingClaim finding) {
            return delegate.owns(finding);
        }

        @Override
        public Optional<String> triageMethod(FindingClaim finding) {
            return delegate.triageMethod(finding);
        }

        @Override
        public Optional<String> rcaMethod(FindingClaim finding) {
            return delegate.rcaMethod(finding);
        }

        @Override
        public Optional<String> evidence(FindingClaim finding, EvidenceContext context) {
            return delegate.evidence(finding, context);
        }

        @Override
        public Optional<ClassifierDetail> detail(FindingRow finding) {
            return delegate.detail(finding);
        }

        @Override
        public void absorbed(FindingRow finding, @Nullable String userId, String now) {
            delegate.absorbed(finding, userId, now);
        }
    }
}
