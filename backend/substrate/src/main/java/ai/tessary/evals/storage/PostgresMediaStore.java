// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import ai.tessary.evals.open.media.MediaStore;
import ai.tessary.evals.tenant.Ids;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed {@link MediaStore}: media bytes as {@code media_object.bytes}
 * ({@code bytea}), content-addressed by sha256 for per-project dedup. The default (and only) MediaStore
 * bean; an object-store implementation can replace it behind the SPI with no caller or schema change.
 */
@Component
public class PostgresMediaStore implements MediaStore {

    private final MediaObjectRepository repo;

    public PostgresMediaStore(MediaObjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public MediaRef put(String projectId, byte[] bytes, String mediaType) {
        String digest = sha256(bytes);
        // Dedup: identical bytes in this project reuse the existing row (stable ref, no duplicate write).
        Optional<String> existing = repo.findIdByDigest(projectId, digest);
        if (existing.isPresent()) {
            return new MediaRef(existing.get());
        }
        String id = Ids.ulid();
        repo.insert(
                id,
                projectId,
                digest,
                mediaType,
                bytes,
                bytes.length,
                Instant.now().toString());
        // A concurrent insert may have won the (project_id, digest) unique key — resolve to the row that
        // stuck so the returned ref is always the canonical one.
        return new MediaRef(repo.findIdByDigest(projectId, digest).orElse(id));
    }

    @Override
    public Optional<StoredMedia> get(String projectId, MediaRef ref) {
        return repo.findById(projectId, ref.id());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JRE", e); // never on a standard JRE
        }
    }
}
