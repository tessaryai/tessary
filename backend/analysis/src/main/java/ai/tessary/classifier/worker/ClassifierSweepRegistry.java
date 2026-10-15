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
 * Every {@link ClassifierSweep} on the classpath, by detector kind. Never filters the catalog:
 * {@code ClassifierService.retireDroppedBuiltIns} irreversibly disables built-in rows whose key leaves it, so shipping
 * is expressed by {@code withheldBuiltInKeys}. Answers {@link Optional}, never a fallback sweep, which would emit
 * findings measuring the wrong thing.
 */
@Component
public class ClassifierSweepRegistry {

    private final Map<String, ClassifierSweep> byKind;

    public ClassifierSweepRegistry(ObjectProvider<ClassifierSweep> sweeps) {
        // ObjectProvider because a build may ship no sweep, and a required empty List fails boot. toUnmodifiableMap
        // fails loud when two beans claim one kind (SpotBugs forbids a constructor throw). A sweep with empty kinds()
        // quietly contributes nothing.
        this.byKind = sweeps.orderedStream()
                .flatMap(sweep -> sweep.kinds().stream().map(kind -> Map.entry(kind, sweep)))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Empty is a normal answer, not an error. */
    public Optional<ClassifierSweep> forKind(String detectorKind) {
        return Optional.ofNullable(byKind.get(detectorKind));
    }

    /** For diagnostics and tests; never a licensing answer. */
    public Set<String> registeredKinds() {
        return byKind.keySet();
    }
}
