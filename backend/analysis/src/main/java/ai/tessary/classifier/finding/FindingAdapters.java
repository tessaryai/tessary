// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.dossier.EvidenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every {@link FindingAdapter} on the classpath, and which one owns a finding. Injected through {@link ObjectProvider}
 * because the open edition ships none, and Spring fails boot on a required collection with no candidates.
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

    /** For tests and hand-built collaborators. */
    public static FindingAdapters of(List<FindingAdapter> adapters) {
        return new FindingAdapters(adapters);
    }

    /** True in the open edition. */
    public boolean isEmpty() {
        return adapters.isEmpty();
    }

    /** Two owners fail loud: which dossier files and absorb hook a finding gets must not depend on classpath order. */
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

    public Optional<String> triageMethod(FindingClaim finding) {
        return ownerOf(finding).flatMap(adapter -> adapter.triageMethod(finding));
    }

    public Optional<String> rcaMethod(FindingClaim finding) {
        return ownerOf(finding).flatMap(adapter -> adapter.rcaMethod(finding));
    }

    public Optional<ClassifierDetail> detail(FindingRow finding) {
        return ownerOf(finding.claim()).flatMap(adapter -> adapter.detail(finding));
    }

    public Optional<String> evidence(FindingClaim finding, EvidenceContext context) {
        return ownerOf(finding).flatMap(adapter -> adapter.evidence(finding, context));
    }
}
