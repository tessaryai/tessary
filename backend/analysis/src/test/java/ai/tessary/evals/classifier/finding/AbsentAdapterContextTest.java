// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ai.tessary.evals.classifier.ClassifierRepository;
import ai.tessary.evals.classifier.ClassifierService;
import ai.tessary.evals.classifier.debug.ClassifierDebugContributor;
import ai.tessary.evals.classifier.debug.ClassifierDebugService;
import ai.tessary.evals.classifier.metric.MetricBaselineRepository;
import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository;
import ai.tessary.evals.classifier.toolerror.ToolErrorReferenceRepository;
import ai.tessary.evals.classifier.toolerror.ToolErrorService;
import ai.tessary.evals.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.evals.classifier.worker.ClassifierJobRepository;
import ai.tessary.evals.storage.AnnotationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The open edition's context STARTS with no drift adapter on the classpath.
 *
 * <p>{@code TriageSourceAbsenceTest} already proves that an absent adapter DEGRADES correctly — an empty
 * list, a null block, a 404 — by handing empty collections to the real constructors. It cannot prove the
 * step before that: whether Spring hands those constructors an empty collection at all. That gap is
 * exactly where #840 and #841 would have failed. Two of the ports have a single implementation each,
 * all {@code @Component}s that #840 took to {@code ai.tessary.paid.classifier.behavior}, and they were
 * injected as plain required
 * {@code List<T>} parameters — so moving that package produced NO compile error, NO import to sever and
 * nothing for {@code check-open-boundary.sh} to grep, while Spring treats a required collection with no
 * candidates as an unsatisfied dependency and refuses to start. A boot failure with a green build, found
 * when a container starts. That move has now happened, and this test is what says it landed quietly.
 *
 * <p>So this file asserts the thing no other file can: a context holding the real
 * {@link FindingService}, {@link BehaviorTriageSource} and {@link ClassifierDebugService}, with every
 * collaborator mocked and NO {@link CauseResolver} or
 * {@link ClassifierDebugContributor} bean at all, comes up — and the ports resolve empty.
 *
 * <p>No Spring Boot application, no database, no Docker: {@link ApplicationContextRunner} is a bean
 * factory, and every collaborator below is a Mockito mock that is never called.
 */
class AbsentAdapterContextTest {

    /**
     * The wiring an edition with no drift classifier produces. {@code @Import} rather than
     * {@code @Bean} methods on purpose: it is the REAL constructor Spring has to satisfy, so a parameter
     * that goes back to {@code List<T>} fails this test rather than passing it in a different shape.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({FindingService.class, BehaviorTriageSource.class, ClassifierDebugService.class})
    static class OpenEditionWiring {

        @Bean
        FindingRepository findings() {
            return mock(FindingRepository.class);
        }

        @Bean
        FindingEvidenceRepository evidence() {
            return mock(FindingEvidenceRepository.class);
        }

        @Bean
        ClassifierRepository classifierRepository() {
            return mock(ClassifierRepository.class);
        }

        @Bean
        ClassifierService classifiers() {
            return mock(ClassifierService.class);
        }

        @Bean
        BehaviorTriageJobRepository triageJobs() {
            return mock(BehaviorTriageJobRepository.class);
        }

        @Bean
        BehaviorTriageEngine triageEngine() {
            return mock(BehaviorTriageEngine.class);
        }

        @Bean
        MetricBaselineRepository metricBaselines() {
            return mock(MetricBaselineRepository.class);
        }

        @Bean
        ToolErrorReferenceRepository toolErrorReferences() {
            return mock(ToolErrorReferenceRepository.class);
        }

        @Bean
        ToolErrorStateRepository toolErrorStates() {
            return mock(ToolErrorStateRepository.class);
        }

        @Bean
        ToolErrorService toolErrors() {
            return mock(ToolErrorService.class);
        }

        @Bean
        BehaviorBaselineEventRepository baselineEvents() {
            return mock(BehaviorBaselineEventRepository.class);
        }

        @Bean
        AnnotationRepository annotations() {
            return mock(AnnotationRepository.class);
        }

        @Bean
        BehaviorSubstrateRepository substrate() {
            return mock(BehaviorSubstrateRepository.class);
        }

        @Bean
        ClassifierJobRepository classifierJobs() {
            return mock(ClassifierJobRepository.class);
        }

        @Bean
        ObjectMapper mapper() {
            return new ObjectMapper();
        }
    }

    @Test
    @DisplayName("with no CauseResolver or debug contributor on the classpath, the context starts")
    void the_open_edition_context_comes_up_with_no_drift_adapters() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenEditionWiring.class)
                .run(ctx -> {
                    assertNull(
                            ctx.getStartupFailure(),
                            "an edition shipping no drift adapter must WIRE, not merely degrade once wired");
                    // And the ports really are empty — the context could otherwise be green because something
                    // else quietly registered an adapter.
                    assertTrue(ctx.getBeansOfType(CauseResolver.class).isEmpty());
                    assertTrue(
                            ctx.getBeansOfType(ClassifierDebugContributor.class).isEmpty());
                    // The shared table's own adapter is open and never absent, which is why THAT port is still a
                    // plain List<TriageSource> and is not part of this claim.
                    assertEquals(1, ctx.getBeansOfType(TriageSource.class).size());
                });
    }
}
