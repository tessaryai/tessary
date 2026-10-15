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
 * Serves media bytes by id for the trace viewer. Part of the free observability layer, so gated by org membership only.
 * Content-addressed ids are immutable, so responses are privately long-cacheable.
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

    /** Falls back to octet-stream on a malformed value. */
    private static MediaType safeType(String t) {
        try {
            return MediaType.parseMediaType(t);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
