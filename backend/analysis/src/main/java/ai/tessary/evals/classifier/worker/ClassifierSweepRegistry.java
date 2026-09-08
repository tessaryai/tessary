// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.worker;

import ai.tessary.evals.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every {@link ClassifierSweep} on the classpath, indexed by the detector kinds it claims.
 *
 * <p>This is the whole of the fitting tier's dispatch. {@link ClassifierWorker} used to hold the four
 * sweeps as constructor fields and pick between them with an {@code if/else} on the kind, which meant
 * the open engine could not compile without every classifier the platform ships — the coupling epic
 * 1's zero-paid-code clause could not tolerate. Now the worker holds one bean and a classifier
 * attaches by being on the classpath.
 *
 * <h2>Two things this deliberately does not do</h2>
 *
 * <p><b>It never touches the catalog.</b> {@link BuiltInClassifierCatalog#builtIns()} is the full set
 * of classifiers the platform DEFINES and is not filtered by what is registered here. Filtering it
 * would be silently destructive: {@code ClassifierService.retireDroppedBuiltIns} permanently disables
 * any seeded {@code built_in=true} row whose key has left the catalog, on a 60-second heartbeat over
 * every active project, and the seeding path then treats the row as "not missing" and never restores
 * it. An edition that shipped a classifier once and then dropped its jar would retire every customer
 * row it wrote, irreversibly, and putting the jar back would not undo it. "This edition does not ship
 * the classifier" is expressed by the flag/capability layer — {@code withheldBuiltInKeys}, which
 * writes nothing — and never by a shorter catalog.
 *
 * <p><b>It answers {@link Optional}, not a default.</b> A kind with no registered sweep is INERT: the
 * worker logs one WARN and completes the job. Handing back some other sweep as a fallback is the
 * exact bug the {@code else} arm in the old dispatch chain was — {@code tool_error} fell into
 * {@code MetricDriftSweep}, whose config parse then defaulted to the full duration/cost measure set,
 * and it maintained a second copy of every baseline and emitted duplicate findings under its own
 * classifier id, green the whole time.
 */
@Component
public class ClassifierSweepRegistry {

    private final Map<String, ClassifierSweep> byKind;

    public ClassifierSweepRegistry(ObjectProvider<ClassifierSweep> sweeps) {
        // ObjectProvider, not List<ClassifierSweep>: an edition may legitimately ship NO sweep at all
        // (nothing on this seam is structurally required), and Spring treats a required collection
        // parameter with no candidates as an unsatisfied dependency rather than an empty list — a boot
        // failure instead of an empty registry.
        //
        // toUnmodifiableMap fails loud on a kind two beans both claim (IllegalStateException), which is
        // the right ending: two sweeps for one kind means two different analyses writing findings under
        // one classifier row, and picking by bean order would make WHICH analysis ran depend on classpath
        // order. Collected rather than thrown from an explicit check for the same reason
        // BuiltInClassifierCatalog does it this way — SpotBugs forbids a constructor throw
        // (CT_CONSTRUCTOR_THROW), so the collector framework carries the invariant.
        //
        // A sweep whose kinds() is empty simply contributes nothing and can never be dispatched. That is
        // left as a quiet no-op rather than a failure: it is indistinguishable from an edition that
        // registers a sweep it has disabled, and failing the whole context over one inert bean would be a
        // worse trade than the classifier not running.
        this.byKind = sweeps.orderedStream()
                .flatMap(sweep -> sweep.kinds().stream().map(kind -> Map.entry(kind, sweep)))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The sweep that claims this {@link BuiltInDetector.Kind}, or empty when nothing on this classpath
     * does. Empty is a normal answer, not an error — see the class comment.
     */
    public Optional<ClassifierSweep> forKind(String detectorKind) {
        return Optional.ofNullable(byKind.get(detectorKind));
    }

    /** The kinds something is registered for, for diagnostics and tests. Never a licensing answer. */
    public Set<String> registeredKinds() {
        return byKind.keySet();
    }
}
