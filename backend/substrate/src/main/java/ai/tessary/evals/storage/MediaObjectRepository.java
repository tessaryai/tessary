// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import ai.tessary.evals.open.media.MediaStore.MediaRef;
import ai.tessary.evals.open.media.MediaStore.StoredMedia;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the {@code media_object} table — externalized,
 * per-project content-addressed media bytes. Same named-parameter pattern as the other substrate repos.
 * The {@code (project_id, digest)} unique key makes {@link #insert} first-write-wins for identical bytes.
 */
@Repository
public class MediaObjectRepository {

    private final JdbcClient jdbc;

    public MediaObjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The existing row id for {@code (projectId, digest)}, or empty — the dedup lookup. */
    public Optional<String> findIdByDigest(String projectId, String digest) {
        return jdbc.sql("SELECT id FROM media_object WHERE project_id = :pid AND digest = :digest")
                .param("pid", projectId)
                .param("digest", digest)
                .query(String.class)
                .optional();
    }

    /** Insert; a concurrent duplicate {@code (project_id, digest)} is a first-write-wins no-op. */
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

    /** Fetch stored media (type + bytes) by id within a project. */
    public Optional<StoredMedia> findById(String projectId, String id) {
        return jdbc.sql("SELECT id, media_type, bytes FROM media_object WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> new StoredMedia(
                        new MediaRef(rs.getString("id")), rs.getString("media_type"), rs.getBytes("bytes")))
                .optional();
    }
}
