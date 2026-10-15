// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.media;

import java.util.Optional;

/**
 * SPI for media bytes kept out of the trace substrate, which carries a {@link MediaRef} instead of inline base64.
 * Content-addressed per project, so an image referenced by many blocks is stored once.
 */
public interface MediaStore {

    /** Idempotent: identical bytes for the same project return the same {@link MediaRef} and write no duplicate row. */
    MediaRef put(String projectId, byte[] bytes, String mediaType);

    Optional<StoredMedia> get(String projectId, MediaRef ref);

    /** The {@code media_object} row id. */
    record MediaRef(String id) {}

    record StoredMedia(MediaRef ref, String mediaType, byte[] bytes) {}
}
