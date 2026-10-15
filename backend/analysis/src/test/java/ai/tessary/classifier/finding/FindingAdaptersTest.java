// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
