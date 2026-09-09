// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.classifier.TestObjectProvider;
import ai.tessary.classifier.catalog.BuiltInClassifierCatalog;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.detector.EncoderScorer;
import ai.tessary.classifier.substrate.ConversationThreadAssembler;
import ai.tessary.classifier.substrate.SubstrateReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The discovery contract, held without a database and without Spring Boot's application context —
 * {@link ApplicationContextRunner} is a plain bean factory, so this runs on any machine.
 *
 * <p>Four things are pinned, and each is a way the registry could have been written that would have
 * looked fine and been wrong: that a bean is found by the kind it CLAIMS rather than by its type or bean
 * name; that two beans claiming one kind fail the context instead of one silently winning by classpath
 * order; that an unclaimed kind answers EMPTY rather than a fallback; and — the one with teeth — that an
 * empty registry does not shrink the catalog.
 */
class ClassifierSweepRegistryTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner();

    /** A sweep claiming exactly the kinds it is built with. Real, not a mock: the registry reads kinds(). */
    private record FixedSweep(Set<String> kinds) implements ClassifierSweep {
        @Override
        public SweepOutcome sweep(SweepContext ctx) {
            return SweepOutcome.EMPTY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoSweeps {
        @Bean
        ClassifierSweep drift() {
            return new FixedSweep(Set.of(BuiltInDetector.Kind.BEHAVIOR_DRIFT));
        }

        @Bean
        ClassifierSweep metrics() {
            return new FixedSweep(Set.of(BuiltInDetector.Kind.DURATION_DRIFT, BuiltInDetector.Kind.COST_DRIFT));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoSweepsClaimingOneKind {
        @Bean
        ClassifierSweep first() {
            return new FixedSweep(Set.of(BuiltInDetector.Kind.SOP_CONFORMANCE));
        }

        @Bean
        ClassifierSweep second() {
            return new FixedSweep(Set.of(BuiltInDetector.Kind.SOP_CONFORMANCE));
        }
    }

    @Test
    @DisplayName("a sweep is found by every kind it claims, and one bean may claim several")
    void every_declared_kind_resolves() {
        context.withUserConfiguration(TwoSweeps.class)
                .withBean(ClassifierSweepRegistry.class)
                .run(ctx -> {
                    ClassifierSweepRegistry registry = ctx.getBean(ClassifierSweepRegistry.class);
                    assertEquals(
                            Set.of(
                                    BuiltInDetector.Kind.BEHAVIOR_DRIFT,
                                    BuiltInDetector.Kind.DURATION_DRIFT,
                                    BuiltInDetector.Kind.COST_DRIFT),
                            Set.copyOf(registry.registeredKinds()));
                    assertTrue(registry.forKind(BuiltInDetector.Kind.COST_DRIFT).isPresent());
                });
    }

    @Test
    @DisplayName("an unregistered kind is EMPTY — never some other sweep")
    void an_unclaimed_kind_answers_empty() {
        // The whole reason forKind returns an Optional. Handing back a default here is the bug the
        // dispatch chain this replaced actually had: tool_error fell into the metric-drift arm, whose
        // config parse then defaulted to the full measure set, and it kept a second copy of every
        // baseline behind a green build.
        context.withUserConfiguration(TwoSweeps.class)
                .withBean(ClassifierSweepRegistry.class)
                .run(ctx -> assertTrue(ctx.getBean(ClassifierSweepRegistry.class)
                        .forKind(BuiltInDetector.Kind.SOP_CONFORMANCE)
                        .isEmpty()));
    }

    @Test
    @DisplayName("an edition that ships no sweep at all still builds a registry rather than failing to start")
    void no_sweeps_is_a_valid_edition() {
        // This is why the constructor takes ObjectProvider rather than List<ClassifierSweep>: Spring
        // treats a required collection with zero candidates as unsatisfied, so the List shape would turn
        // "this build ships no fitting-tier classifier" into a boot failure.
        context.withBean(ClassifierSweepRegistry.class).run(ctx -> {
            assertFalse(ctx.getStartupFailure() != null, "an empty registry is a valid edition, not a failure");
            assertTrue(
                    ctx.getBean(ClassifierSweepRegistry.class).registeredKinds().isEmpty());
        });
    }

    @Test
    @DisplayName("two sweeps claiming one kind fail the context; neither silently wins")
    void a_duplicate_kind_fails_startup() {
        // Picking one would make WHICH analysis ran depend on bean order, and both write findings under
        // the same classifier row, so the row would read as one classifier that cannot make up its mind.
        context.withUserConfiguration(TwoSweepsClaimingOneKind.class)
                .withBean(ClassifierSweepRegistry.class)
                .run(ctx -> assertTrue(
                        ctx.getStartupFailure() != null
                                && rootCauseMessage(ctx.getStartupFailure())
                                        .contains(BuiltInDetector.Kind.SOP_CONFORMANCE),
                        "a kind claimed twice must name itself in the failure"));
    }

    /**
     * The invariant with teeth: what is REGISTERED never decides what the catalog CONTAINS.
     *
     * <p>{@code ClassifierService.retireDroppedBuiltIns} permanently disables any seeded
     * {@code built_in=true} row whose key has left {@code catalog.builtIns()}, on a 60-second heartbeat
     * over every active project, and the seeding path then treats the row as "not missing" and never
     * restores it. So a registry that filtered the catalog would mean any database that had ever run the
     * paid build silently and irreversibly retires both paid classifiers the first time it runs the open
     * one — and re-adding the jar would not bring them back. Withheld-because-unlicensed and
     * retired-because-dropped are two different endings and only one of them writes.
     */
    @Test
    @DisplayName("an empty registry does not shrink the catalog — absence is not withdrawal")
    void the_catalog_is_not_filtered_by_what_is_registered() {
        ClassifierSweepRegistry empty = new ClassifierSweepRegistry(TestObjectProvider.of(List.of()));
        assertTrue(empty.registeredKinds().isEmpty());

        List<String> keys = catalog().builtIns().stream()
                .map(BuiltInClassifierCatalog.BuiltIn::classifierKey)
                .toList();
        assertTrue(
                keys.contains(BuiltInDetector.Kind.BEHAVIOR_DRIFT),
                "behaviour drift must stay in the catalog in an edition that does not ship its sweep");
        assertTrue(
                keys.contains(BuiltInDetector.Kind.SOP_CONFORMANCE),
                "and so must SOP conformance; the flag layer withholds them, the catalog never drops them");
    }

    private static BuiltInClassifierCatalog catalog() {
        return new BuiltInClassifierCatalog(
                new ObjectMapper(),
                mock(SubstrateReadRepository.class),
                mock(EncoderScorer.class),
                mock(ConversationThreadAssembler.class),
                TestObjectProvider.of(List.of()));
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }
}
