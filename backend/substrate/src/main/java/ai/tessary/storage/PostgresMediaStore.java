// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.open.media.MediaStore;
import ai.tessary.pricing.PriceSnapshot;
import ai.tessary.tenant.Ids;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link MediaStore} on {@code media_object.bytes}, content-addressed by sha256 per project. */
@Component
public class PostgresMediaStore implements MediaStore {

    private final MediaObjectRepository repo;

    public PostgresMediaStore(MediaObjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public MediaRef put(String projectId, byte[] bytes, String mediaType) {
        String digest = PriceSnapshot.sha256Hex(bytes);
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
        // A concurrent insert may have won the unique key; return the row that stuck.
        return new MediaRef(repo.findIdByDigest(projectId, digest).orElse(id));
    }

    @Override
    public Optional<StoredMedia> get(String projectId, MediaRef ref) {
        return repo.findById(projectId, ref.id());
    }
}
