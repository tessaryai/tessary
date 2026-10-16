// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.ClassifierDtos.ClassifierView;
import ai.tessary.classifier.ClassifierDtos.SetCallSitesRequest;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import ai.tessary.detection.DetectionTableRegistry;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The classifier list says, per row, whether the trace views show its flags, so the views can offer exactly those
 * classifiers. The bug is a flag computed from something other than the registry, which lists a classifier whose
 * flags no trace view marks, or hides one they do.
 */
class ClassifierControllerTest {

    private final ClassifierService service = mock(ClassifierService.class);
    private final ClassifierReset reset = mock(ClassifierReset.class);
    private final TenantContext ctx = mock(TenantContext.class);

    private ClassifierController controller(String role) {
        TenantPathResolver resolver = mock(TenantPathResolver.class);
        when(resolver.requireProject(any(), any(), any()))
                .thenReturn(new TenantPathResolver.Resolved(
                        new Organization("org_1", null, "acme", "Acme", "t0", null, null),
                        new Project("prj_1", "org_1", "web", "Web", null, "t0", null, null, true, null),
                        role));
        DetectionTableRegistry registry = new DetectionTableRegistry(TestObjectProvider.of(
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE, true, true),
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        return new ClassifierController(service, reset, mock(GroundednessStatus.class), resolver, registry);
    }

    @Test
    void theViewSaysWhetherTheTraceViewsShowTheClassifier() {
        when(service.list("prj_1"))
                .thenReturn(List.of(
                        ClassifierRowBuilder.of("frustration").id("c-f").build(),
                        ClassifierRowBuilder.of("secret_leak").id("c-s").build(),
                        ClassifierRowBuilder.of("unregistered").id("c-u").build()));

        List<ClassifierView> views =
                Objects.requireNonNull(controller("owner").list(ctx, "acme", "web").data());

        assertEquals(
                List.of("c-f true", "c-s false", "c-u false"),
                views.stream().map(v -> v.id() + " " + v.shownInTraceViews()).toList());
    }

    /** Catches a reset or a call-site scope that a viewer, who may only look, can write. */
    @ParameterizedTest
    @ValueSource(strings = {"member", "viewer"})
    void onlyAManagerResetsAClassifierOrScopesItsCallSites(String role) {
        ClassifierController controller = controller(role);
        SetCallSitesRequest scope = new SetCallSitesRequest(List.of("cs-a"));
        if (role.equals("viewer")) {
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    assertThrows(ResponseStatusException.class, () -> controller.reset(ctx, "acme", "web", "c-1"))
                            .getStatusCode());
            assertEquals(
                    HttpStatus.FORBIDDEN,
                    assertThrows(
                                    ResponseStatusException.class,
                                    () -> controller.setCallSites(ctx, "acme", "web", "c-1", scope))
                            .getStatusCode());
            verifyNoInteractions(reset, service);
            return;
        }
        when(reset.reset("prj_1", "c-1", null))
                .thenReturn(ClassifierRowBuilder.of("secret_leak").id("c-1").build());
        when(service.setCallSiteIds("prj_1", "c-1", List.of("cs-a")))
                .thenReturn(ClassifierRowBuilder.of("secret_leak").id("c-1").build());
        when(service.knownCallSiteIds("prj_1")).thenReturn(List.of("cs-a", "cs-b"));

        assertEquals("c-1", Objects.requireNonNull(controller.reset(ctx, "acme", "web", "c-1").data()).id());
        assertEquals(
                "c-1",
                Objects.requireNonNull(controller.setCallSites(ctx, "acme", "web", "c-1", scope).data()).id());
        assertEquals(List.of("cs-a", "cs-b"), controller.callSites(ctx, "acme", "web").data());
    }
}
