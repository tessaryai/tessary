// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.model.CallSite;
import ai.tessary.model.Pipeline;
import ai.tessary.pipeline.PipelineController.PipelineEnvelope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The pipeline read the SPA renders from, and the reload button that is kept as a 200 no-op. The bugs:
 * serving another project's pipeline, and an envelope the frontend's parser no longer accepts.
 */
@SpringBootTest
class PipelineControllerTest {

    @Autowired
    TenantService tenants;

    @Autowired
    PipelineService pipelines;

    @Autowired
    PipelineController controller;

    @Test
    void getAndReloadServeTheProjectsOwnPipelineInTheStableEnvelope() {
        var fix = TenantFixture.bootstrap(tenants, "pipeline-read");
        pipelines.upsert(fix.project().id(), pipelineWith("cs_answer"));
        TenantContext owner = new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);

        PipelineEnvelope got = requireNonNull(
                controller.get(owner, fix.org().slug(), fix.project().slug()).data());
        PipelineEnvelope reloaded = requireNonNull(
                controller.reload(owner, fix.org().slug(), fix.project().slug()).data());

        Pipeline stored = pipelines.getPipeline(fix.project().id());
        assertEquals(
                new PipelineEnvelope(true, "db://project/" + fix.project().id(), List.of(), List.of(), stored), got);
        assertEquals(got, reloaded, "reload is a no-op read, since the database is the source of truth");
        assertEquals(
                List.of("cs_answer"),
                got.pipeline().callSites().stream().map(CallSite::id).toList());
    }

    @Test
    void anotherOrgsUserCannotReadThePipeline() {
        var fix = TenantFixture.bootstrap(tenants, "pipeline-owner");
        var outsider = TenantFixture.bootstrap(tenants, "pipeline-outsider");
        TenantContext ctx =
                new TenantContext(outsider.user().id(), outsider.user().email(), null, null, null, null);

        ResponseStatusException e = assertThrows(
                ResponseStatusException.class,
                () -> controller.get(ctx, fix.org().slug(), fix.project().slug()));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode(), "a non-member is refused before any read");
    }

    private static Pipeline pipelineWith(String callSiteId) {
        return new Pipeline(
                "0.8.0",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                null,
                List.of(new CallSite(
                        callSiteId,
                        null,
                        "sdk",
                        "openai",
                        "gpt-5",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of())),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of());
    }
}
