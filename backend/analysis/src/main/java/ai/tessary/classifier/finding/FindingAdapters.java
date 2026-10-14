// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.dossier.EvidenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every {@link FindingAdapter} on the classpath, and the one lookup consumers use: which adapter owns a finding.
 *
 * <p>Injected through {@link ObjectProvider} rather than as a {@code List<FindingAdapter>}: the open edition ships no
 * adapter, and Spring treats a required collection parameter with no candidates as an unsatisfied dependency, a boot
 * failure instead of an empty set.
 */
@Component
public final class FindingAdapters {

    private final List<FindingAdapter> adapters;

    @Autowired
    public FindingAdapters(ObjectProvider<FindingAdapter> adapters) {
        this(adapters.orderedStream().toList());
    }

    private FindingAdapters(List<FindingAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    /** A fixed set, for tests and for any caller that builds its collaborators by hand. */
    public static FindingAdapters of(List<FindingAdapter> adapters) {
        return new FindingAdapters(adapters);
    }

    /** True when no adapter is registered: the open edition. */
    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    /**
     * The one adapter that owns this finding, or empty when none does. Two owners fail loud: which classifier's
     * dossier files and absorb hook a finding gets must not depend on classpath order.
     */
    public Optional<FindingAdapter> ownerOf(FindingClaim finding) {
        FindingAdapter owner = null;
        for (FindingAdapter adapter : adapters) {
            if (!adapter.owns(finding)) continue;
            if (owner != null) {
                throw new IllegalStateException("finding " + finding.id() + " is owned by both "
                        + owner.getClass().getName() + " and "
                        + adapter.getClass().getName());
            }
            owner = adapter;
        }
        return Optional.ofNullable(owner);
    }

    /** The owner's triage {@code method.md}, or empty when no adapter owns the finding or the owner has none. */
    public Optional<String> triageMethod(FindingClaim finding) {
        return ownerOf(finding).flatMap(adapter -> adapter.triageMethod(finding));
    }

    /** The owner's RCA {@code method.md}, or empty when no adapter owns the finding or the owner has none. */
    public Optional<String> rcaMethod(FindingClaim finding) {
        return ownerOf(finding).flatMap(adapter -> adapter.rcaMethod(finding));
    }

    /** The owner's page detail for this finding, or empty when no adapter owns it or the owner reports none. */
    public Optional<ClassifierDetail> detail(FindingRow finding) {
        return ownerOf(finding.claim()).flatMap(adapter -> adapter.detail(finding));
    }

    /** The owner's RCA {@code evidence.md}, or empty when no adapter owns the finding or the owner has none. */
    public Optional<String> evidence(FindingClaim finding, EvidenceContext context) {
        return ownerOf(finding).flatMap(adapter -> adapter.evidence(finding, context));
    }
}
