// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shared fixture for integration tests that need a user + org + project to
 * exercise project-scoped behaviour. Centralised here so that bumps to the
 * tenant data model don't require touching every test class.
 *
 * <p>Each call mints a fresh user with a unique WorkOS-id so concurrent or
 * back-to-back tests don't collide on the {@code app_user.workos_user_id}
 * uniqueness constraint.</p>
 *
 * <p>Use sparingly: tests covering tenancy *semantics* (e.g. the personal-vs-
 * WorkOS-org branches in {@code TenantService}) should bootstrap by hand.</p>
 */
public final class TenantFixture {

    private static final AtomicLong COUNTER = new AtomicLong();

    private TenantFixture() {}

    public record Setup(Principal user, Organization org, Project project) {}

    /**
     * Create a fresh user, ensure a personal org for them, and create one
     * project under it. {@code testName} is folded into the user email + project
     * name to keep failure reports readable.
     */
    public static Setup bootstrap(TenantService tenants, String testName) {
        return bootstrap(tenants, testName, org -> {});
    }

    /**
     * As {@link #bootstrap(TenantService, String)}, but runs {@code beforeProject} once the org exists and
     * <em>before</em> its project is created.
     *
     * <p>The window matters because project creation seeds the built-in classifiers, and which built-ins it
     * seeds is decided by the org's capabilities at that moment. A test that grants a capability after
     * bootstrapping has already missed the seed: the classifier is never inserted, and the test then fails
     * looking for findings from a classifier that does not exist for the project. This overload is where a
     * capability grant belongs.
     */
    public static Setup bootstrap(TenantService tenants, String testName, Consumer<Organization> beforeProject) {
        long n = COUNTER.incrementAndGet();
        Principal user = tenants.upsertUserFromWorkos(
                "user_" + testName + "_" + n, testName + "+" + n + "@example.com", testName + " tester", null);
        Organization org = tenants.ensureDefaultOrg(user, null);
        beforeProject.accept(org);
        Project project = tenants.createProject(org.id(), testName + "-" + n, null);
        return new Setup(user, org, project);
    }
}
