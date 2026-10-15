// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.classifier.ClassifierDtos.ClassifierView;
import ai.tessary.classifier.detector.groundedness.GroundednessStatus;
import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import ai.tessary.detection.DetectionTableRegistry;
import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Project;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The classifier list says, per row, whether the trace views show its flags, so the views can offer exactly those
 * classifiers. The bug is a flag computed from something other than the registry, which lists a classifier whose
 * flags no trace view marks, or hides one they do.
 */
class ClassifierControllerTest {

    @Test
    void theViewSaysWhetherTheTraceViewsShowTheClassifier() {
        ClassifierService service = mock(ClassifierService.class);
        when(service.list("prj_1"))
                .thenReturn(List.of(
                        ClassifierRowBuilder.of("frustration").id("c-f").build(),
                        ClassifierRowBuilder.of("secret_leak").id("c-s").build(),
                        ClassifierRowBuilder.of("unregistered").id("c-u").build()));
        TenantPathResolver resolver = mock(TenantPathResolver.class);
        when(resolver.requireProject(any(), any(), any()))
                .thenReturn(new TenantPathResolver.Resolved(
                        new Organization("org_1", null, "acme", "Acme", "t0", null, null),
                        new Project("prj_1", "org_1", "web", "Web", null, "t0", null, null, true, null),
                        "owner"));
        DetectionTableRegistry registry = new DetectionTableRegistry(TestObjectProvider.of(
                new DetectionTable("frustration", "frustration_detection", Grain.TRACE, true, true),
                new DetectionTable("secret_leak", "secret_leak_detection", Grain.SPAN, false, false)));
        ClassifierController controller = new ClassifierController(
                service, mock(ClassifierReset.class), mock(GroundednessStatus.class), resolver, registry);

        List<ClassifierView> views = Objects.requireNonNull(
                controller.list(mock(TenantContext.class), "acme", "web").data());

        assertEquals(
                List.of("c-f true", "c-s false", "c-u false"),
                views.stream().map(v -> v.id() + " " + v.shownInTraceViews()).toList());
    }
}
