// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The package-by-feature conventions from {@code docs/reference/architecture.md} and the
 * coding rules from {@code AGENTS.md}, enforced as tests. Hard-fail: a violation breaks
 * the build. Runs inside {@code mvn test} via the ArchUnit JUnit 5 engine.
 */
@AnalyzeClasses(packages = "ai.tessary.evals", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    private static final String BASE = "ai.tessary.evals";

    // ---- Universal coding rules (AGENTS.md) — built into ArchUnit ----

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
            "logging goes through SLF4J, never System.out/err (AGENTS.md logging conventions)");

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.because(
            "services and controllers wrap failures in EvalsException(ErrorCode, ...), never raw RuntimeException");

    @ArchTest
    static final ArchRule no_java_util_logging =
            NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.because("logging is SLF4J + Logback, not java.util.logging");

    @ArchTest
    static final ArchRule loggers_are_private_static_final = fields().that()
            .haveRawType("org.slf4j.Logger")
            .should()
            .haveModifier(JavaModifier.PRIVATE)
            .andShould()
            .haveModifier(JavaModifier.STATIC)
            .andShould()
            .haveModifier(JavaModifier.FINAL)
            .because(
                    "the canonical declaration is `private static final Logger log = LoggerFactory.getLogger(X.class)`");

    // ---- Package-by-feature hard rules (docs/reference/architecture.md) ----

    @ArchTest
    static final ArchRule controllers_live_in_feature_slices = noClasses()
            .that()
            .areMetaAnnotatedWith("org.springframework.stereotype.Controller")
            .should()
            .resideInAPackage("..web..")
            .because("controllers live in their feature slice; web/ is shared HTTP plumbing only");

    @ArchTest
    static final ArchRule web_is_http_plumbing_only = classes()
            .that()
            .resideInAPackage(BASE + ".web..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    BASE + ".web..",
                    BASE + ".open.errors..",
                    "java..",
                    "javax..",
                    "jakarta..",
                    "org.springframework..",
                    "com.fasterxml.jackson..",
                    "org.slf4j..")
            .because(
                    "web/ is the shared envelope + exception advice — it must not reach into feature slices or model/");

    @ArchTest
    static final ArchRule model_is_pure_data = classes()
            .that()
            .resideInAPackage(BASE + ".model..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    BASE + ".model..",
                    "java..",
                    "com.fasterxml.jackson..",
                    // Annotation-only metadata (no behavior / no framework coupling): JSpecify nullability and
                    // the OpenAPI @Schema descriptors that make the generated contract faithful.
                    "org.jspecify..",
                    "io.swagger.v3.oas.annotations..")
            .because("model/ is the pure synth-schema record graph — no Spring, no feature-slice dependencies");

    @ArchTest
    static final ArchRule model_has_no_spring_stereotypes = noClasses()
            .that()
            .resideInAPackage(BASE + ".model..")
            .should()
            .beMetaAnnotatedWith("org.springframework.stereotype.Component")
            .because(
                    "model/ holds plain data records, never @Component/@Service/@Repository/@Controller/@Configuration");

    @ArchTest
    static final ArchRule error_codes_live_in_errors = classes()
            .that()
            .implement(BASE + ".open.errors.ErrorCode")
            .should()
            .resideInAPackage(BASE + ".open.errors..")
            .because("every per-domain error enum lives in errors/ (AGENTS.md error handling)");

    @ArchTest
    static final ArchRule jdbc_client_only_in_repositories = noClasses()
            .that()
            .haveSimpleNameNotEndingWith("Repository")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .because("raw JdbcClient access belongs in *Repository classes; everything else goes through them");

    /**
     * The pipeline definition store, pinned by name for
     * {@link #definition_repositories_accessed_only_within_their_feature} — and therefore listed in
     * {@link #PINNED_CLASS_NAMES}, because a string-anchored rule empties silently when its subject moves.
     */
    private static final String PIPELINE_REPOSITORY = BASE + ".pipeline.PipelineRepository";

    // CURATION_REPOSITORY was pinned here beside PIPELINE_REPOSITORY until Track A deleted the
    // curation overlay outright. every_pinned_class_name_resolves is what caught it: a
    // string-anchored rule whose subject no longer exists is vacuous, and vacuous is exactly the
    // failure mode that rule was written to make loud.
    @ArchTest
    static final ArchRule definition_repositories_accessed_only_within_their_feature = noClasses()
            .that()
            .resideOutsideOfPackage(BASE + ".pipeline..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(PIPELINE_REPOSITORY)
            .because("the pipeline definition store is read/written through its service facade "
                    + "(PipelineService); a cross-feature caller must not reach into the repository");

    // A slice-acyclicity rule was evaluated and deliberately NOT adopted: features depend back on
    // config (EvalsProperties) and on each other across slices, so the feature graph is cyclic by
    // design. The directional rules above (web-plumbing-only, model-pure, controller placement)
    // capture the layering intent instead.

    /**
     * Every Layer-2 lane, by name. Spec invariant 9 — analysis never mutates detector state — is a rule
     * about the LAYER, not about the lane that happened to exist when it was written. Two instruments
     * now: the sandbox triage lane ({@code BehaviorTriageEngine} / {@code Worker}) and RCA
     * ({@code RcaAnalysisService}). Both are injected the finding repository because both legitimately
     * read a finding and record a ruling on it, which is exactly what makes the one-line edit that
     * resolves the finding compile.
     *
     * <p>{@code GraderRunWorker} was the third and left with Track A. Note this string is NOT covered by
     * {@link #PINNED_CLASS_NAMES} — it is a regex over simple names, not a fully-qualified pin, so a
     * stale alternative here goes unnoticed by the very rule that exists to catch stale pins. Checked by
     * hand on removal, which is the only check available.
     */
    private static final String LAYER_2_LANES = ".*(BehaviorTriage(Engine|Worker)|RcaAnalysisService)";

    /**
     * Behaviour drift used to be pinned here by five names: its package, the allowlist, the gram state
     * machine, the epoch store and its sweep. #840 moved the whole directory to
     * {@code ai.tessary.paid.classifier.behavior}, which is outside this class's
     * {@code @AnalyzeClasses} root and off {@code app}'s classpath entirely — so every one of those
     * rules would have gone VACUOUS rather than failing, which is the exact defect
     * {@link #every_pinned_class_name_resolves} exists to catch. They are deleted, as the previous
     * javadoc here said they would be, and their job transfers to {@code scripts/check-open-boundary.sh}
     * rule 1: a text grep over every open source file that self-arms on the overlay's own packages, and
     * so covers strings, javadoc and yaml that ArchUnit never saw either.
     *
     * <p>SOP conformance went the same way in #841, and took the same four names with it. #841 left a
     * shrunk {@code classifier.conformance} package open — the controller, the two spec-pinned view
     * records and the {@code FitReportSource} port — for a reason that no longer holds: #917/#944
     * replaced the one-shared-spec rule with a per-edition spec, and #918 took those last four (five,
     * counting the test that constructed the controller directly) the rest of the way to
     * {@code tessary-paid/conformance}. There is no {@code classifier.conformance} package left in the
     * open tree at all now, so there is nothing left here for the rules below to have an opinion about.
     */
    private static final String METRIC_SWEEP = BASE + ".classifier.metric.MetricDriftSweep";

    private static final String TOOL_ERROR_SWEEP = BASE + ".classifier.toolerror.ToolErrorSweep";

    /**
     * Every fully-qualified {@code ai.tessary.evals} name this file pins, so
     * {@link #every_pinned_class_name_resolves} can prove they all still name a class. Add to this list
     * whenever a rule below anchors on a new one — the third-party names ({@code JdbcClient}) are out of
     * scope because the scan only imports our own packages, and their owners break the build on rename.
     *
     * <p><b>This list exists because the failure it catches has already happened once.</b>
     * {@code behaviour_triage_never_mutates_the_baseline} pinned {@code classifier.BehaviorAllowlistRepository}
     * while the class sat at {@code classifier.behavior.BehaviorAllowlistRepository}: a package move had
     * silently emptied the rule, so it matched nothing, passed green, and guarded nothing for as long as
     * it took anyone to look. An ArchUnit rule anchored on a string is invisible to the compiler, which
     * is exactly why the strings need a rule of their own.
     */
    private static final List<String> PINNED_CLASS_NAMES = List.of(METRIC_SWEEP, TOOL_ERROR_SWEEP, PIPELINE_REPOSITORY);

    /*
     * <b>{@code behaviour_triage_never_mutates_the_baseline} used to be here, and #840 deleted it.</b>
     * PROGRAM.md §8.1 calls an automatic "expected" the one unrecoverable error in behaviour drift: the
     * triage agent reads ONE exemplar trace, and writing the allowlist from that would permanently blind
     * the detector to a whole drift class. The rule banned {@link #LAYER_2_LANES} from depending on
     * {@code BehaviorAllowlistRepository} or {@code BehaviorNgramRepository}, and it was held
     * structurally because the worker is legitimately injected the full finding repository, so the
     * one-line edit that breaks the invariant otherwise compiles.
     *
     * <p>Both of those repositories are now {@code ai.tessary.paid.classifier.behavior}, and the Layer-2
     * lanes are open. The invariant did not weaken — it got STRONGER, and stopped being expressible
     * here at the same moment: an open class cannot depend on a paid one at all. The enforcer fails the
     * reactor at {@code validate} and {@code check-open-boundary.sh} rule 1 fails on the package name
     * appearing in an open file even in a comment. Keeping the rule with the old strings would have left
     * it matching nothing and passing green, which is worse than not having it — see
     * {@link #every_pinned_class_name_resolves}, added after exactly that happened to this rule once
     * before.
     *
     * <p>{@link #behaviour_triage_never_resolves_a_finding} below is the half that is still expressible,
     * because {@code FindingRepository} stays open and shared.
     */
    /**
     * Every fully-qualified name pinned in this file still names a class that exists.
     *
     * <p>A rule that names nothing is not a weak rule, it is no rule — and it looks identical to a
     * passing one from the build output. This is the guard for that, and it is the reason
     * {@code behaviour_triage_never_mutates_the_baseline} above could be trusted after #839 moved the
     * package it points at.
     */
    @ArchTest
    static final ArchRule every_pinned_class_name_resolves = classes()
            .that()
            .resideInAPackage(BASE + "..")
            .should(coverEveryPinnedName())
            .because("an ArchUnit rule anchored on a fully-qualified string is invisible to the compiler, "
                    + "so a package move empties it silently and it keeps passing");

    /*
     * <b>Two conformance rules used to be here, and #841 deleted them.</b>
     * {@code the_findings_feature_does_not_depend_on_conformance_storage} banned
     * {@code classifier.finding} from reaching {@code classifier.conformance.store}, and
     * {@code only_the_relocating_case_source_reaches_into_conformance} banned everything else from
     * reaching conformance at all, with a named exception for {@code ConformanceCaseSource} — whose own
     * javadoc said "when #841 lands, this exception is deleted with the class". It did, and it was.
     *
     * <p>Both rules were preconditions for the extraction, and the extraction happened: the store, the
     * scoring engine, the sweep and the case source are {@code ai.tessary.paid.classifier.conformance}
     * now, and an open class cannot depend on one at all. Repointing them at the paid package was not an
     * option — {@code @AnalyzeClasses} is rooted at {@code ai.tessary.evals} and the paid jar is not on
     * {@code app}'s classpath — so the rules would have gone VACUOUS rather than red. Their job belongs
     * to the enforcer at {@code validate} and to {@code check-open-boundary.sh} rule 1, which is a text
     * grep and therefore also catches the javadoc and string references ArchUnit never saw.
     *
     * <p>{@code ai.tessary.evals.classifier.conformance} used to keep a shrunk wire seam open —
     * {@code ConformanceController}, {@code ConformanceDtos} and {@code FitReportSource} — because the
     * checked-in OpenAPI spec was generated from the open context. #917/#944 replaced the one-shared-spec
     * rule with a per-edition spec, and #918 took those last four files (five, counting their test) the
     * rest of the way to {@code tessary-paid/conformance}. There is no {@code classifier.conformance}
     * package left in the open tree at all now, so there is nothing left here for either deleted rule to
     * have had an opinion about.
     */
    /**
     * A classifier's sweep is reached through the seam or not at all.
     *
     * <p>The four concrete sweeps this rule was written for were constructor fields on
     * {@code ClassifierWorker} until #876,
     * which is what made the open engine uncompilable without both paid classifiers. Now they are
     * discovered as {@code ClassifierSweep} beans, and this rule is what stops the shortcut coming back:
     * an {@code if} on the detector kind plus one injected sweep would compile, pass every other check,
     * and quietly re-couple the engine to a classifier it must not name.
     *
     * <p>The names are pinned as strings, so {@link #every_pinned_class_name_resolves} is what keeps this
     * rule from going vacuous when one of them moves. #840 and #841 took {@code BehaviorDriftSweep} and
     * {@code ConformanceSweep} to {@code ai.tessary.paid.*}, which is why there are two arms below and
     * not four: those two were deleted rather than re-anchored, and their job transferred to
     * {@code scripts/check-open-boundary.sh} rule 1, which self-arms on the overlay's own packages. What
     * remains guards the two open classifiers, and is the rule a THIRD open sweep would join.
     */
    @ArchTest
    static final ArchRule a_sweep_is_reached_only_from_its_own_classifier = noClasses()
            .that()
            .resideOutsideOfPackage(BASE + ".classifier.metric..")
            .and()
            .resideOutsideOfPackage(BASE + ".classifier.toolerror..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(METRIC_SWEEP)
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName(TOOL_ERROR_SWEEP)
            .because("the fitting tier is dispatched through ClassifierSweep; an open class holding a "
                    + "concrete sweep is the coupling #876 removed and the reason the engine could not "
                    + "compile without the paid classifiers");

    /**
     * The other half of the same invariant: triage must not RESOLVE a finding either. Both
     * writes live on {@code FindingRepository}, which the worker is legitimately injected for
     * (it reads the finding and records the verdict), so this has to be enforced per method.
     */
    @ArchTest
    static final ArchRule behaviour_triage_never_resolves_a_finding = noClasses()
            .that()
            .haveNameMatching(LAYER_2_LANES)
            .should()
            .callMethodWhere(target(nameMatching("setStatus|resolve|resolveForNativeCause"))
                    .and(target(owner(nameMatching(".*FindingRepository")))))
            .because("resolving a finding is a human decision (tessary-paid/classifiers/behavior_drift/PROGRAM.md §9); "
                    + "an automatic 'expected' from a single exemplar would blind the detector permanently");

    // ---- catalog parity (custom conditions) ----

    @ArchTest
    static final ArchRule error_enums_registered_in_catalog = classes()
            .that()
            .implement(BASE + ".open.errors.ErrorCode")
            .should(beListedInErrorCatalog())
            .because("an ErrorCode enum missing from ErrorCatalog.REGISTERED is never validated for "
                    + "cross-domain code uniqueness at boot");

    /**
     * Every rule here scans {@code ai.tessary.evals}, which since the module split lives almost
     * entirely in JARs on this module's classpath rather than in {@code app}'s own sources. A
     * classpath that stopped carrying them would make each rule above vacuously true and this
     * whole file a green no-op, so the scan asserts its own reach: the floor is well under the
     * real count and only trips if a layer has genuinely dropped off.
     *
     * <p><b>The floor moved 1500 → 1100 with Track A</b>, which deleted a whole Maven module and 213
     * main classes; the real count went from ~1700 to 1261. Lowering a tripwire because it tripped is
     * usually the wrong move, and it is the right one here for one reason: the count fell because the
     * classes are GONE, not because a module fell off the classpath, and the two are distinguishable —
     * a missing module JAR drops hundreds at once and still lands under 1100.
     */
    @ArchTest
    static final ArchRule the_scan_reaches_every_module = classes()
            .that()
            .resideInAPackage(BASE + "..")
            .should(numberMoreThan(1100))
            .because("a rule that scanned nothing would pass, and every rule in this file scans "
                    + "code that now arrives as a module dependency rather than as local sources");

    // ---- helpers ----

    /** Fails unless the whole scanned set is larger than {@code floor}. */
    private static ArchCondition<JavaClass> numberMoreThan(int floor) {
        return new ArchCondition<>("number more than " + floor + " classes in total") {
            private int seen;

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                seen++;
            }

            @Override
            public void finish(ConditionEvents events) {
                events.add(new SimpleConditionEvent(
                        seen,
                        seen > floor,
                        "scanned only " + seen + " classes under " + BASE + "; expected more than " + floor
                                + " — the module JARs are missing from the test classpath"));
            }
        };
    }

    /**
     * Fails naming the ones that resolved to nothing, rather than reporting "0 violations" like a rule
     * whose subject has simply vanished.
     */
    private static ArchCondition<JavaClass> coverEveryPinnedName() {
        return new ArchCondition<>("cover every fully-qualified class name pinned in this file") {
            private final Set<String> unseen = new java.util.LinkedHashSet<>(PINNED_CLASS_NAMES);

            @Override
            public void check(JavaClass item, ConditionEvents events) {
                unseen.remove(item.getName());
            }

            @Override
            public void finish(ConditionEvents events) {
                events.add(new SimpleConditionEvent(
                        unseen,
                        unseen.isEmpty(),
                        "these fully-qualified names are pinned by a rule in this file and match no class "
                                + "on the scanned classpath, so the rules naming them are vacuous: " + unseen));
            }
        };
    }

    /** Enum classes listed in {@code ErrorCatalog.REGISTERED}. */
    private static final Set<String> REGISTERED_ERROR_TYPES = registeredErrorTypeNames();

    private static ArchCondition<JavaClass> beListedInErrorCatalog() {
        return new ArchCondition<>("be listed in ErrorCatalog.REGISTERED") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean registered = REGISTERED_ERROR_TYPES.contains(normalize(item.getName()));
                events.add(new SimpleConditionEvent(
                        item,
                        registered,
                        registered
                                ? item.getName() + " is registered"
                                : item.getName() + " is NOT listed in ErrorCatalog.REGISTERED"));
            }
        };
    }

    private static String normalize(String className) {
        return className.replace('$', '.');
    }

    @SuppressWarnings("unchecked")
    private static Set<String> registeredErrorTypeNames() {
        try {
            Field field = Class.forName(BASE + ".open.errors.ErrorCatalog").getDeclaredField("REGISTERED");
            field.setAccessible(true);
            List<Class<?>> registered = (List<Class<?>>) field.get(null);
            return registered.stream().map(c -> normalize(c.getName())).collect(Collectors.toUnmodifiableSet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read ErrorCatalog.REGISTERED reflectively", e);
        }
    }
}
