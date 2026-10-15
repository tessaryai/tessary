// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sources.SourceDtos.CreateSourceRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SourceService {

    public static final String FAKE_PROVIDER = "fake";

    /** Never network-fetched; one per project (see {@link #ensureSdkSource}). */
    public static final String SDK_PROVIDER = "sdk";

    private static final String SDK_SOURCE_NAME = "Tessary SDK";

    /** Satisfies the {@code @NotBlank} contract for a non-network source. */
    private static final String SDK_BASE_URL = "tessary://sdk";

    private static final Set<String> SUPPORTED = Set.of(FAKE_PROVIDER, SDK_PROVIDER);

    private final SourceRepository sources;

    public SourceService(SourceRepository sources) {
        this.sources = sources;
    }

    public List<SourceRow> list(String projectId) {
        return sources.findAllForProject(projectId);
    }

    public SourceRow create(String projectId, CreateSourceRequest req) {
        if (!SUPPORTED.contains(req.provider())) {
            throw new TessaryException(IngestError.UNSUPPORTED_PROVIDER, req.provider());
        }
        // A per-project singleton, so connect routes to the idempotent ensure path; re-connecting is a no-op rather
        // than a DUPLICATE_NAME error.
        if (SDK_PROVIDER.equals(req.provider())) {
            return ensureSdkSource(projectId);
        }
        if (sources.findByName(projectId, req.name()).isPresent()) {
            throw new TessaryException(IngestError.DUPLICATE_NAME, req.name());
        }
        String now = Instant.now().toString();
        // Every supported provider is synthetic: no network fetch, so no SSRF guard and no credential seal.
        SourceRow row = new SourceRow(
                UUID.randomUUID().toString(), projectId, req.provider(), req.name(), req.baseUrl(), "", now, now);
        sources.insert(row);
        return row;
    }

    /**
     * Keyed on the provider, not the display name, so it is a per-project singleton. Carries no credentials and creates
     * no dataset.
     */
    public SourceRow ensureSdkSource(String projectId) {
        return sources.findAllForProject(projectId).stream()
                .filter(s -> SDK_PROVIDER.equals(s.provider()))
                .findFirst()
                .orElseGet(() -> {
                    String now = Instant.now().toString();
                    SourceRow row = new SourceRow(
                            UUID.randomUUID().toString(),
                            projectId,
                            SDK_PROVIDER,
                            SDK_SOURCE_NAME,
                            SDK_BASE_URL,
                            "",
                            now,
                            now);
                    sources.insert(row);
                    return row;
                });
    }
}
