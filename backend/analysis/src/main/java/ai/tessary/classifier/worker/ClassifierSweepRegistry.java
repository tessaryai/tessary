// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Every {@link ClassifierSweep} on the classpath, indexed by the detector kinds it claims. A
 * classifier attaches to {@link ClassifierWorker} by being on the classpath, rather than by a
 * constructor field and an {@code if/else} on the kind, so the build does not require every
 * classifier the platform ships.
 *
 * <h2>Two things this deliberately does not do</h2>
 *
 * <p>It never touches the catalog. {@link BuiltInClassifierCatalog#builtIns()} is the full set
 * of classifiers the platform defines and is not filtered by what is registered here. Filtering
 * it would be silently destructive: {@code ClassifierService.retireDroppedBuiltIns} permanently
 * disables any seeded {@code built_in=true} row whose key has left the catalog, on a 60-second
 * heartbeat over every active project, and the seeding path then treats the row as "not missing"
 * and never restores it. A build that shipped a classifier once and then dropped its jar would
 * retire every customer row it wrote, irreversibly, and putting the jar back would not undo it.
 * Whether a build ships a given classifier is expressed by the flag/capability layer
 * ({@code withheldBuiltInKeys}, which writes nothing), never by a shorter catalog.
 *
 * <p>It answers {@link Optional}, not a default. A kind with no registered sweep is inert: the
 * worker logs one warning and completes the job. Handing back some other sweep as a fallback
 * would be a bug: a kind silently scored by the wrong sweep's config would emit findings under
 * its own classifier id while actually measuring something else, green the whole time.
 */
@Component
public class ClassifierSweepRegistry {

    private final Map<String, ClassifierSweep> byKind;

    public ClassifierSweepRegistry(ObjectProvider<ClassifierSweep> sweeps) {
        // ObjectProvider, not List<ClassifierSweep>: a build may legitimately ship no sweep at
        // all, and Spring treats a required collection parameter with no candidates as an
        // unsatisfied dependency rather than an empty list, a boot failure instead of an empty
        // registry.
        //
        // toUnmodifiableMap fails loud on a kind two beans both claim (IllegalStateException):
        // two sweeps for one kind means two different analyses writing findings under one
        // classifier row, and picking by bean order would make which analysis ran depend on
        // classpath order. Collected rather than thrown from an explicit check because SpotBugs
        // forbids a constructor throw (CT_CONSTRUCTOR_THROW), so the collector framework carries
        // the invariant.
        //
        // A sweep whose kinds() is empty simply contributes nothing and can never be dispatched.
        // That is a quiet no-op rather than a failure, since it is indistinguishable from a sweep
        // registered but disabled, and failing the whole context over one inert bean would be a
        // worse trade than the classifier not running.
        this.byKind = sweeps.orderedStream()
                .flatMap(sweep -> sweep.kinds().stream().map(kind -> Map.entry(kind, sweep)))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The sweep that claims this {@link BuiltInDetector.Kind}, or empty when nothing on this classpath
     * does. Empty is a normal answer, not an error; see the class comment.
     */
    public Optional<ClassifierSweep> forKind(String detectorKind) {
        return Optional.ofNullable(byKind.get(detectorKind));
    }

    /** The kinds something is registered for, for diagnostics and tests. Never a licensing answer. */
    public Set<String> registeredKinds() {
        return byKind.keySet();
    }
}
