// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.media;

import java.util.Optional;

/**
 * SPI for externalized media bytes.
 * The trace substrate carries a {@link MediaRef} instead of inline base64; the bytes live out-of-band
 * in a MediaStore, content-addressed per project so the same image referenced by many blocks is stored
 * once.
 *
 * <p>Open SPI: this interface is part of the open substrate; the implementation (Postgres {@code bytea}
 * today, an object store later) is closed and swappable without touching callers or the schema.
 */
public interface MediaStore {

    /**
     * Store {@code bytes} for {@code projectId}, returning a stable, content-addressed reference.
     * Idempotent: storing identical bytes for the same project returns the same {@link MediaRef} and
     * writes no duplicate row.
     */
    MediaRef put(String projectId, byte[] bytes, String mediaType);

    /** Fetch stored media by reference within a project, or empty when absent. */
    Optional<StoredMedia> get(String projectId, MediaRef ref);

    /** An opaque, stable handle to stored media (the {@code media_object} row id). */
    record MediaRef(String id) {}

    /** Stored media: its reference, declared media type, and raw bytes. */
    record StoredMedia(MediaRef ref, String mediaType, byte[] bytes) {}
}
