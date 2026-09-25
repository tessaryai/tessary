// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Stored media served back to the trace viewer: the project's own bytes, under the type they were stored as. */
@SpringBootTest
class MediaControllerTest {

    @Autowired
    MediaController controller;

    @Autowired
    MediaStore media;

    @Autowired
    TenantService tenants;

    private record Tenant(TenantContext ctx, String org, String proj, String pid) {}

    private Tenant tenant(String slug) {
        var fix = TenantFixture.bootstrap(tenants, slug);
        return new Tenant(
                new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null),
                fix.org().slug(),
                fix.project().slug(),
                fix.project().id());
    }

    @Test
    void mediaIsServedToItsOwnProjectOnlyUnderItsStoredType() {
        Tenant mine = tenant("media-mine");
        Tenant theirs = tenant("media-theirs");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        MediaRef ref = media.put(mine.pid(), png, "image/png");

        ResponseEntity<byte[]> served = controller.get(mine.ctx(), mine.org(), mine.proj(), ref.id());
        assertEquals(HttpStatus.OK, served.getStatusCode());
        assertEquals(MediaType.IMAGE_PNG, served.getHeaders().getContentType());
        assertEquals("max-age=31536000, private", served.getHeaders().getCacheControl());
        assertArrayEquals(png, served.getBody());

        assertEquals(
                HttpStatus.NOT_FOUND,
                controller
                        .get(theirs.ctx(), theirs.org(), theirs.proj(), ref.id())
                        .getStatusCode(),
                "another project's media id is not found, not served");
    }

    /** A stored type that does not parse is served as opaque bytes rather than failing the request. */
    @Test
    void mediaWithAnUnparseableStoredTypeIsServedAsOpaqueBytes() {
        Tenant t = tenant("media-odd-type");
        MediaRef ref = media.put(t.pid(), new byte[] {1, 2, 3}, "not a media type");

        ResponseEntity<byte[]> served = controller.get(t.ctx(), t.org(), t.proj(), ref.id());

        assertEquals(HttpStatus.OK, served.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, served.getHeaders().getContentType());
    }
}
