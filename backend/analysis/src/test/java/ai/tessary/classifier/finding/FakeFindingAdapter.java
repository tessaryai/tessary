// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.classifier.finding.dossier.EvidenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link FindingAdapter} that owns one classifier key, hands back fixed dossier texts, and records each
 * {@code absorbed} call and the {@link EvidenceContext} it was handed. A hand-written adapter reads better than a mock
 * script, and tests in {@code rca} and {@code cases} use it too.
 */
public final class FakeFindingAdapter implements FindingAdapter {

    /** One {@code absorbed} call, as the adapter received it. */
    public record Absorbed(String findingId, @Nullable String userId, String now) {}

    private final String ownedKey;
    private final @Nullable String triageMethod;
    private final @Nullable String rcaMethod;
    private final @Nullable String evidence;
    private final @Nullable RuntimeException failure;
    private final List<Absorbed> calls = new ArrayList<>();
    private final List<EvidenceContext> evidenceContexts = new ArrayList<>();

    private FakeFindingAdapter(
            String ownedKey,
            @Nullable String triageMethod,
            @Nullable String rcaMethod,
            @Nullable String evidence,
            @Nullable RuntimeException failure) {
        this.ownedKey = ownedKey;
        this.triageMethod = triageMethod;
        this.rcaMethod = rcaMethod;
        this.evidence = evidence;
        this.failure = failure;
    }

    /**
     * Owns {@code ownedKey} and hands back the three texts as its triage {@code method.md}, RCA {@code method.md} and
     * RCA {@code evidence.md}; a null text is a file the classifier does not have.
     */
    public static FakeFindingAdapter owning(
            String ownedKey, @Nullable String triageMethod, @Nullable String rcaMethod, @Nullable String evidence) {
        return new FakeFindingAdapter(ownedKey, triageMethod, rcaMethod, evidence, null);
    }

    /** Owns {@code ownedKey} and throws {@code failure} from every {@code absorbed} call. */
    public static FakeFindingAdapter failingToAbsorb(String ownedKey, RuntimeException failure) {
        return new FakeFindingAdapter(ownedKey, "# Method\n", "# Method\n", null, failure);
    }

    @Override
    public boolean owns(FindingClaim finding) {
        return ownedKey.equals(finding.classifierKey());
    }

    @Override
    public Optional<String> triageMethod(FindingClaim finding) {
        return Optional.ofNullable(triageMethod);
    }

    @Override
    public Optional<String> rcaMethod(FindingClaim finding) {
        return Optional.ofNullable(rcaMethod);
    }

    @Override
    public Optional<String> evidence(FindingClaim finding, EvidenceContext context) {
        evidenceContexts.add(context);
        return Optional.ofNullable(evidence);
    }

    @Override
    public void absorbed(FindingRow finding, @Nullable String userId, String now) {
        if (failure != null) throw failure;
        calls.add(new Absorbed(finding.id(), userId, now));
    }

    /** Every {@code absorbed} call so far, oldest first. */
    public List<Absorbed> absorbedCalls() {
        return List.copyOf(calls);
    }

    /** Every context an {@code evidence} call was handed, oldest first. */
    public List<EvidenceContext> evidenceContexts() {
        return List.copyOf(evidenceContexts);
    }
}
