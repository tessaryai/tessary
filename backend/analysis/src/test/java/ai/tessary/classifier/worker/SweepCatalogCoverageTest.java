// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierModelModule.Grain;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.metric.MetricDriftSweep;
import ai.tessary.classifier.substrate.ConversationThreadAssembler;
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
 * {@link ClassifierSweepRegistryTest} proves the registry's own behaviour with {@code FixedSweep} fakes,
 * and {@code ClassifierWorkerLoggingTest} stubs {@code kinds()} on mocks — neither reads what the real
 * sweeps actually declare, so neither can see a kind fall off one.
 *
 * <p>The failure it exists to catch is silent and total. Dispatch used to send an unmatched WINDOW kind
 * into {@code MetricDriftSweep}; since #876 it is inert by design — one WARN, {@code markSwept}, done —
 * which is right for an edition that does not ship a classifier and wrong for a typo. Drop
 * {@code COST_DRIFT} from {@link MetricDriftSweep#kinds()} and cost drift stops writing findings for every
 * project on every deployment, while {@code task backend:check}, {@code backend:check:open},
 * {@code check-open-boundary.sh} and every ArchUnit rule stay green. The only signal is one
 * {@code signal.sweep.no-handler} WARN per job, in a log nobody reads until a customer asks where their
 * findings went.
 *
 * <p>The sweeps are built reflectively with mocked collaborators rather than through a Spring context:
 * {@code kinds()} is a declaration, not behaviour, so nothing here needs a wired bean, a database, or a
 * container — which is the point, because the Testcontainers suite does not run on every machine.
 * A new constructor parameter needs no edit here; a new sweep does, and that is the intended friction.
 *
 * <p><b>Two of the four sweeps are not on this module's classpath any more</b> (#840, #841), and that is
 * why the invariant is stated in two pieces rather than one list. The catalog is NOT filtered by
 * edition — {@code BEHAVIOR_DRIFT} and {@code SOP_CONFORMANCE} are defined in every build, because
 * {@code ClassifierService.retireDroppedBuiltIns} keys on catalog membership and retirement is
 * irreversible — so their kinds are still in {@code fittingTierKinds} here while nothing open can name
 * the sweeps that claim them. Naming those two kinds in {@link #KINDS_WITH_A_PAID_SWEEP} keeps the
 * assertion an EQUALITY, which is what makes it catch things: drop {@code COST_DRIFT} from
 * {@link MetricDriftSweep#kinds()} and it is unclaimed and not excluded, so this still fails; add a
 * catalog kind with no sweep and it still fails; retire a paid classifier from the catalog without
 * deleting its line below and the exclusion is left over, so it fails from the other side too. The
 * paid half — that each moved sweep really does claim the kind excluded for it — is pinned inside the
 * overlay by {@code BehaviorDriftSweepClaimsItsKindTest} and {@code ConformanceSweepClaimsItsKindTest};
 * neither half is sufficient alone.
 */
class SweepCatalogCoverageTest {

    /** Every sweep in the OPEN tree today. A new one is added here, and the list is the enumeration. */
    private static final List<Class<? extends ClassifierSweep>> OPEN_SWEEPS =
            List.of(MetricDriftSweep.class, ToolErrorSweep.class);

    /**
     * The catalog kinds whose sweep lives in {@code tessary-paid/} and so cannot be named from here.
     * Not a list of kinds to ignore — every one of these is still asserted to be a fitting-tier kind the
     * catalog defines, and a line left here for a kind the catalog dropped fails this test.
     */
    private static final Set<String> KINDS_WITH_A_PAID_SWEEP =
            Set.of(BuiltInDetector.Kind.BEHAVIOR_DRIFT, BuiltInDetector.Kind.SOP_CONFORMANCE);

    @Test
    @DisplayName(
            "every TRACE/WINDOW catalog kind is claimed by exactly one sweep, and no sweep claims a kind the catalog does not define")
    void fitting_tier_kinds_and_registered_sweeps_agree() {
        BuiltInClassifierCatalog catalog = catalog();

        Set<String> fittingTierKinds = new TreeSet<>();
        for (BuiltInClassifierCatalog.BuiltIn builtIn : catalog.builtIns()) {
            Grain grain = catalog.grainFor(builtIn.detector());
            if (grain == Grain.TRACE || grain == Grain.WINDOW) fittingTierKinds.add(builtIn.detector());
        }

        Set<String> claimed = new TreeSet<>();
        for (Class<? extends ClassifierSweep> type : OPEN_SWEEPS) {
            for (String kind : instantiate(type).kinds()) {
                if (!claimed.add(kind)) {
                    throw new AssertionError("two sweeps claim " + kind
                            + "; the registry rejects that at startup, so this is a build-time catch");
                }
                if (KINDS_WITH_A_PAID_SWEEP.contains(kind)) {
                    throw new AssertionError("an open sweep claims " + kind + ", which is excluded here as "
                            + "belonging to a paid sweep; the registry rejects two claimants at startup, so "
                            + "either the exclusion is stale or the claim is a typo");
                }
            }
        }

        Set<String> handled = new TreeSet<>(claimed);
        handled.addAll(KINDS_WITH_A_PAID_SWEEP);

        // Compared as whole sets on purpose: a one-way containment check would pass while a sweep claimed a
        // kind the catalog no longer defines, which is the same bug seen from the other end.
        assertEquals(
                fittingTierKinds,
                handled,
                "the catalog's fitting-tier kinds and the kinds the sweeps claim have drifted apart; "
                        + "an unclaimed kind is a classifier that silently stops producing findings, and a "
                        + "claimed-or-excluded kind the catalog does not define is the same bug from the "
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

    /** {@code ObjectMapper} is a real one — it is a value, not a collaborator, and mocking it buys nothing. */
    private static Object stub(Class<?> type) {
        return type == ObjectMapper.class ? new ObjectMapper() : mock(type);
    }

    private static BuiltInClassifierCatalog catalog() {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                mock(SubstrateReadRepository.class),
                mock(EncoderScorer.class),
                mock(ConversationThreadAssembler.class),
                TestObjectProvider.of(List.of()));
    }
}
