// SPDX-License-Identifier: Apache-2.0
package ai.tessary.arch;

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
 * Package-by-feature conventions from {@code devdocs/reference/architecture.md} and the coding
 * rules from {@code AGENTS.md}, enforced as tests via ArchUnit JUnit 5.
 */
@AnalyzeClasses(packages = "ai.tessary", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    private static final String BASE = "ai.tessary";

    // ---- Universal coding rules from AGENTS.md, built into ArchUnit ----

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
            "logging goes through SLF4J, never System.out/err (AGENTS.md logging conventions)");

    @ArchTest
    static final ArchRule no_generic_exceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.because(
            "services and controllers wrap failures in TessaryException(ErrorCode, ...), never raw RuntimeException");

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

    // ---- Package-by-feature hard rules (devdocs/reference/architecture.md) ----

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
                    // Annotation-only: JSpecify nullability and the OpenAPI @Schema descriptors, no
                    // framework coupling.
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
     * The pipeline definition store, pinned for {@link #definition_repositories_accessed_only_within_their_feature}
     * and listed in {@link #PINNED_CLASS_NAMES} so a rename does not silently empty the rule.
     */
    private static final String PIPELINE_REPOSITORY = BASE + ".pipeline.PipelineRepository";

    @ArchTest
    static final ArchRule definition_repositories_accessed_only_within_their_feature = noClasses()
            .that()
            .resideOutsideOfPackage(BASE + ".pipeline..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(PIPELINE_REPOSITORY)
            .because("the pipeline definition store is read/written through its service facade "
                    + "(PipelineService); a cross-feature caller must not reach into the repository");

    /**
     * Every Layer-2 lane, by name: the sandbox triage lane ({@code BehaviorTriageEngine} /
     * {@code Worker}) and RCA ({@code RcaAnalysisService}). Both are injected the finding
     * repository because both legitimately read a finding and record a ruling on it, which is
     * exactly what makes the one-line edit that resolves the finding compile.
     *
     * <p>This is a regex over simple names, not a fully-qualified pin, so it is not covered by
     * {@link #PINNED_CLASS_NAMES}; check it by hand if a lane is renamed or removed.
     */
    private static final String LAYER_2_LANES = ".*(BehaviorTriage(Engine|Worker)|RcaAnalysisService)";

    /** Pinned so {@link #a_sweep_is_reached_only_from_its_own_classifier} tracks the current class name. */
    private static final String METRIC_SWEEP = BASE + ".classifier.metric.MetricDriftSweep";

    private static final String TOOL_ERROR_SWEEP = BASE + ".classifier.toolerror.ToolErrorSweep";

    /**
     * Every fully-qualified {@code ai.tessary} name this file pins, so
     * {@link #every_pinned_class_name_resolves} can prove they all still name a class. Add to this list
     * whenever a rule below anchors on a new one; third-party names like {@code JdbcClient} are out of
     * scope since the scan only imports our own packages, and their owners break the build on rename.
     *
     * <p>This list exists because a pinned rule has silently gone vacuous before: a package move left
     * the string matching nothing, so the rule passed green while guarding nothing.
     */
    private static final List<String> PINNED_CLASS_NAMES = List.of(METRIC_SWEEP, TOOL_ERROR_SWEEP, PIPELINE_REPOSITORY);

    /**
     * Every fully-qualified name pinned in this file still names a class that exists.
     *
     * <p>A rule that names nothing is not a weak rule, it is no rule, and it looks identical to a
     * passing one from the build output. This is the guard for that.
     */
    @ArchTest
    static final ArchRule every_pinned_class_name_resolves = classes()
            .that()
            .resideInAPackage(BASE + "..")
            .should(coverEveryPinnedName())
            .because("an ArchUnit rule anchored on a fully-qualified string is invisible to the compiler, "
                    + "so a package move empties it silently and it keeps passing");

    /**
     * A classifier's sweep is reached through the seam or not at all: sweeps are discovered as
     * {@code ClassifierSweep} beans, and this rule stops an {@code if} on detector kind plus one
     * injected sweep from quietly re-coupling the engine to a classifier it must not name.
     *
     * <p>The names are pinned as strings, so {@link #every_pinned_class_name_resolves} keeps this
     * rule from going vacuous when one of them moves. This is the rule a third open sweep would join.
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
                    + "concrete sweep creates a compile-time coupling to the classifier implementations "
                    + "this rule prevents");

    /**
     * Triage must not resolve a finding. The worker is legitimately injected {@code FindingRepository}
     * (it reads the finding and records the verdict), so this is enforced per method rather than by
     * banning the dependency outright.
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
     * Every rule here scans {@code ai.tessary}, which lives mostly in JARs on this module's
     * classpath rather than in {@code app}'s own sources. A classpath that stopped carrying them
     * would make each rule above vacuously true and this whole file a green no-op, so the scan
     * asserts its own reach: the floor sits well under the real count and only trips if a layer
     * has genuinely dropped off.
     *
     * <p>A missing module JAR drops hundreds of classes at once, so the floor stays low enough
     * that a real, deliberate reduction in class count does not trip it by mistake.
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
