// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A {@link FindingAdapter} that owns one classifier key, hands back a fixed {@code method.md}, and records each
 * {@code absorbed} call. A hand-written adapter reads better than a mock script, and tests in {@code rca} and
 * {@code cases} use it too.
 */
public final class FakeFindingAdapter implements FindingAdapter {

    /** One {@code absorbed} call, as the adapter received it. */
    public record Absorbed(String findingId, @Nullable String userId, String now) {}

    private final String ownedKey;
    private final @Nullable String method;
    private final @Nullable RuntimeException failure;
    private final List<Absorbed> calls = new ArrayList<>();

    private FakeFindingAdapter(String ownedKey, @Nullable String method, @Nullable RuntimeException failure) {
        this.ownedKey = ownedKey;
        this.method = method;
        this.failure = failure;
    }

    /** Owns {@code ownedKey} and hands back {@code method} as its {@code method.md}. */
    public static FakeFindingAdapter owning(String ownedKey, String method) {
        return new FakeFindingAdapter(ownedKey, method, null);
    }

    /** Owns {@code ownedKey} and throws {@code failure} from every {@code absorbed} call. */
    public static FakeFindingAdapter failingToAbsorb(String ownedKey, RuntimeException failure) {
        return new FakeFindingAdapter(ownedKey, "# Method\n", failure);
    }

    @Override
    public boolean owns(FindingClaim finding) {
        return ownedKey.equals(finding.classifierKey());
    }

    @Override
    public Optional<String> method(FindingClaim finding) {
        return Optional.ofNullable(method);
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
}
