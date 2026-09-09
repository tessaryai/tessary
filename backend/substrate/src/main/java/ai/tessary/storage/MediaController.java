// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves externalized media bytes by id — the read side of the {@link MediaStore}
 * seam that the frontend trace viewer renders images from ({@code <img src=".../media/{id}">}) and
 * that the judge boundary re-hydrates refs through. Media rides with traces, part of the FREE
 * observability layer, so it is gated by org membership only ({@code requireProject}), not an
 * entitlement. Returns raw bytes with the stored content type; content-addressed ids are immutable,
 * so responses are long-cacheable (private). 404 when the ref is unknown.
 *
 * <p>{@code media_object} is populated by the ingest-side media externalization, which writes
 * {@code ContentBlock.imageRef} instead of inline base64.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/media")
public class MediaController {

    private final TenantPathResolver resolver;
    private final MediaStore media;

    public MediaController(TenantPathResolver resolver, MediaStore media) {
        this.resolver = resolver;
        this.media = media;
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<byte[]> get(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String mediaId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        return media.get(r.project().id(), new MediaRef(mediaId))
                .<ResponseEntity<byte[]>>map(m -> ResponseEntity.ok()
                        .contentType(safeType(m.mediaType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate())
                        .body(m.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Parse the stored media type, falling back to octet-stream on a malformed value. */
    private static MediaType safeType(String t) {
        try {
            return MediaType.parseMediaType(t);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
