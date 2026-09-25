// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.metric.MetricDriftSweep;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import ai.tessary.classifier.toolerror.ToolErrorSweep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every catalog kind that sweeps at a fitting tier has exactly one sweep that claims it.
 *
 * <p>This is the invariant the registry depends on and the one nothing else pins.
 * {@link ClassifierSweepRegistryTest} exercises the registry's own behaviour with {@code FixedSweep}
 * fakes, and {@code ClassifierWorkerLoggingTest} stubs {@code kinds()} on mocks; neither reads what
 * the real sweeps declare, so neither can see a kind fall off one.
 *
 * <p>The failure is silent and total: an unmatched kind is inert by design, one WARN and
 * {@code markSwept}, so dropping a kind from a sweep's {@code kinds()} stops that classifier from
 * writing findings for every project on every deployment, while every other check stays green. The
 * only signal is a {@code signal.sweep.no-handler} WARN per job.
 *
 * <p>Sweeps are built reflectively with mocked collaborators rather than through a Spring context,
 * since {@code kinds()} is a declaration, not behaviour: nothing here needs a wired bean, a database,
 * or a container. A new constructor parameter needs no edit here; a new sweep does.
 */
class SweepCatalogCoverageTest {

    /** Every sweep this tree defines. A new one is added here, and the list is the enumeration. */
    private static final List<Class<? extends ClassifierSweep>> OPEN_SWEEPS =
            List.of(MetricDriftSweep.class, ToolErrorSweep.class);

    @Test
    @DisplayName(
            "every WINDOW catalog kind is claimed by exactly one sweep, and no sweep claims a kind the catalog does not define")
    void fitting_tier_kinds_and_registered_sweeps_agree() {
        BuiltInClassifierCatalog catalog = catalog();

        Set<String> fittingTierKinds = new TreeSet<>();
        for (BuiltInClassifierCatalog.BuiltIn builtIn : catalog.builtIns()) {
            Grain grain = catalog.grainFor(builtIn.detector());
            if (grain == Grain.WINDOW) fittingTierKinds.add(builtIn.detector());
        }

        Set<String> claimed = new TreeSet<>();
        for (Class<? extends ClassifierSweep> type : OPEN_SWEEPS) {
            for (String kind : instantiate(type).kinds()) {
                if (!claimed.add(kind)) {
                    throw new AssertionError("two sweeps claim " + kind
                            + "; the registry rejects that at startup, so this is a build-time catch");
                }
            }
        }

        // Compared as whole sets on purpose: a one-way containment check would pass while a sweep claimed a
        // kind the catalog no longer defines, which is the same bug seen from the other end.
        assertEquals(
                fittingTierKinds,
                claimed,
                "the catalog's fitting-tier kinds and the kinds the sweeps claim have drifted apart; "
                        + "an unclaimed kind is a classifier that silently stops producing findings, and a "
                        + "claimed kind the catalog does not define is the same bug from the "
                        + "other end");
    }

    private static ClassifierSweep instantiate(Class<? extends ClassifierSweep> type) {
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(SweepCatalogCoverageTest::stub)
                .toArray();
        try {
            ctor.setAccessible(true);
            return (ClassifierSweep) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build " + type.getSimpleName() + " with mocked collaborators", e);
        }
    }

    /** {@code ObjectMapper} is a real one: it is a value, not a collaborator, and mocking it buys nothing. */
    private static Object stub(Class<?> type) {
        return type == ObjectMapper.class ? new ObjectMapper() : mock(type);
    }

    private static BuiltInClassifierCatalog catalog() {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                mock(SubstrateReadRepository.class),
                mock(EncoderScorer.class),
                TestObjectProvider.of(List.of()));
    }
}
