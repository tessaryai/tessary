// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sources.SourceDtos.CreateSourceRequest;
import ai.tessary.sources.SourceDtos.SourceResponse;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ingestion-source API: a source belongs to one project, and every read and delete is scoped to it.
 */
@SpringBootTest
class SourceControllerTest {

    @Autowired
    SourceController controller;

    @Autowired
    TenantService tenants;

    private record Tenant(TenantContext ctx, String org, String proj) {}

    private Tenant tenant(String slug) {
        var fix = TenantFixture.bootstrap(tenants, slug);
        return new Tenant(
                new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null),
                fix.org().slug(),
                fix.project().slug());
    }

    @Test
    void aSourceIsReadAndDeletedOnlyThroughItsOwnProject() {
        Tenant mine = tenant("sources-mine");
        Tenant theirs = tenant("sources-theirs");
        var req = new CreateSourceRequest("fake", "staging", "https://example.test", Map.of());

        SourceResponse created = ok(controller.create(mine.ctx(), mine.org(), mine.proj(), req));
        assertEquals(
                new SourceResponse(
                        created.id(),
                        "fake",
                        "staging",
                        "https://example.test",
                        created.createdAt(),
                        created.createdAt()),
                created);
        assertEquals(
                created,
                ok(controller.get(mine.ctx(), mine.org(), mine.proj(), created.id())),
                "reads back every field it was written with");
        assertEquals(List.of(created), ok(controller.list(mine.ctx(), mine.org(), mine.proj())));

        assertEquals(List.of(), ok(controller.list(theirs.ctx(), theirs.org(), theirs.proj())));
        assertEquals(
                IngestError.SOURCE_NOT_FOUND,
                error(() -> controller.get(theirs.ctx(), theirs.org(), theirs.proj(), created.id())),
                "another project cannot read the source by its id");
        assertEquals(
                IngestError.SOURCE_NOT_FOUND,
                error(() -> controller.delete(theirs.ctx(), theirs.org(), theirs.proj(), created.id())),
                "nor delete it");

        assertEquals(
                IngestError.DUPLICATE_NAME, error(() -> controller.create(mine.ctx(), mine.org(), mine.proj(), req)));
        assertEquals(
                IngestError.UNSUPPORTED_PROVIDER,
                error(() -> controller.create(
                        mine.ctx(),
                        mine.org(),
                        mine.proj(),
                        new CreateSourceRequest("s3", "bucket", "https://example.test", Map.of()))));

        assertEquals(
                true,
                ok(controller.delete(mine.ctx(), mine.org(), mine.proj(), created.id()))
                        .deleted());
        assertEquals(
                IngestError.SOURCE_NOT_FOUND,
                error(() -> controller.get(mine.ctx(), mine.org(), mine.proj(), created.id())));
        assertEquals(
                IngestError.SOURCE_NOT_FOUND,
                error(() -> controller.delete(mine.ctx(), mine.org(), mine.proj(), created.id())),
                "a second delete reports the source gone rather than succeeding again");
    }

    @Test
    void connectingTheSdkSourceTwiceReturnsTheSameSingleSource() {
        Tenant t = tenant("sources-sdk");

        SourceResponse first = ok(controller.create(
                t.ctx(), t.org(), t.proj(), new CreateSourceRequest("sdk", "mine", "https://x.test", Map.of())));
        SourceResponse again = ok(controller.create(
                t.ctx(), t.org(), t.proj(), new CreateSourceRequest("sdk", "other", "https://y.test", Map.of())));

        assertEquals(first, again, "a reconnect reuses the project's sdk source");
        assertEquals("Tessary SDK", first.name());
        assertEquals("tessary://sdk", first.baseUrl(), "the sdk source is never network-fetched");
        assertEquals(List.of(first), ok(controller.list(t.ctx(), t.org(), t.proj())));
    }

    private static ErrorCode error(Executable call) {
        return assertThrows(TessaryException.class, call).error();
    }

    private static <T> T ok(ApiResponse<T> response) {
        T data = response.data();
        assertNotNull(data, "the envelope carried no data");
        return data;
    }
}
