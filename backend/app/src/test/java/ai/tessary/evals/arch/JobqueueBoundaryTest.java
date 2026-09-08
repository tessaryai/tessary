// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Open/closed boundary guard for the module split: the {@code jobqueue} package is the seed of the OPEN
 * {@code shared} module, so it must stay free of any tessary feature-package dependency — otherwise it
 * cannot be extracted into the open repo cleanly. This rule fails the build the moment any code makes
 * {@code jobqueue} depend on a feature package, enforcing the boundary before the modules physically
 * exist. As more packages become boundary-clean, add their rules here / in the eventual module guards.
 */
@AnalyzeClasses(packages = "ai.tessary.evals", importOptions = ImportOption.DoNotIncludeTests.class)
public class JobqueueBoundaryTest {

    @ArchTest
    static final ArchRule jobqueue_stays_dependency_free = classes()
            .that()
            .resideInAPackage("..jobqueue..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("..jobqueue..", "java..", "org.jspecify..")
            .because("jobqueue is the seed of the open `shared` module (Phase 0) and must not depend on "
                    + "any tessary feature package, so it stays cleanly extractable");
}
