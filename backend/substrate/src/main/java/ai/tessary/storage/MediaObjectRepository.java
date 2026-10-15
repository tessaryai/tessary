// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.open.media.MediaStore.StoredMedia;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@code media_object}. The {@code (project_id, digest)} unique key makes {@link #insert}
 * first-write-wins.
 */
@Repository
public class MediaObjectRepository {

    private final JdbcClient jdbc;

    public MediaObjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findIdByDigest(String projectId, String digest) {
        return jdbc.sql("SELECT id FROM media_object WHERE project_id = :pid AND digest = :digest")
                .param("pid", projectId)
                .param("digest", digest)
                .query(String.class)
                .optional();
    }

    public void insert(
            String id,
            String projectId,
            String digest,
            String mediaType,
            byte[] bytes,
            long sizeBytes,
            String createdAt) {
        jdbc.sql("""
                        INSERT INTO media_object (id, project_id, digest, media_type, bytes, size_bytes, created_at)
                        VALUES (:id, :pid, :digest, :mediaType, :bytes, :sizeBytes, :createdAt)
                        ON CONFLICT (project_id, digest) DO NOTHING
                        """)
                .param("id", id)
                .param("pid", projectId)
                .param("digest", digest)
                .param("mediaType", mediaType)
                .param("bytes", bytes)
                .param("sizeBytes", sizeBytes)
                .param("createdAt", createdAt)
                .update();
    }

    public Optional<StoredMedia> findById(String projectId, String id) {
        return jdbc.sql("SELECT id, media_type, bytes FROM media_object WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> new StoredMedia(
                        new MediaRef(rs.getString("id")), rs.getString("media_type"), rs.getBytes("bytes")))
                .optional();
    }
}
