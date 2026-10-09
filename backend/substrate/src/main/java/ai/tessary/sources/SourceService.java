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

    /** Provider tag for the synthetic in-memory upstream source. */
    public static final String FAKE_PROVIDER = "fake";

    /**
     * Provider tag for the synthetic source backing data ingested through the OTLP receiver.
     * It is never network-fetched. One per project (see {@link #ensureSdkSource}).
     */
    public static final String SDK_PROVIDER = "sdk";

    private static final String SDK_SOURCE_NAME = "Tessary SDK";

    /** Sentinel baseUrl for synthetic, non-network sources — satisfies the {@code @NotBlank} contract. */
    private static final String SDK_BASE_URL = "tessary://sdk";

    private static final Set<String> SUPPORTED = Set.of(FAKE_PROVIDER, SDK_PROVIDER);

    private final SourceRepository sources;

    public SourceService(SourceRepository sources) {
        this.sources = sources;
    }

    public List<SourceRow> list(String projectId) {
        return sources.findAllForProject(projectId);
    }

    public SourceRow get(String projectId, String id) {
        return sources.findById(projectId, id)
                .orElseThrow(() -> new TessaryException(IngestError.SOURCE_NOT_FOUND, id));
    }

    public SourceRow create(String projectId, CreateSourceRequest req) {
        if (!SUPPORTED.contains(req.provider())) {
            throw new TessaryException(IngestError.UNSUPPORTED_PROVIDER, req.provider());
        }
        // The "sdk" source is a per-project singleton over the substrate — there is no per-source config to
        // collect, so route the connect call to the idempotent ensure path. This makes re-connecting (the
        // "sdk" tab issues a token then creates the source) a no-op rather than a DUPLICATE_NAME error.
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
     * Returns the project's reusable "sdk" source, creating it on first use — the substrate-backed source
     * that makes OTLP-ingested telemetry a selectable dataset source. It is keyed on the synthetic provider
     * (not the display name) so it is a per-project singleton, and it carries no credentials.
     * A source auto-creates no dataset — it is just a source.
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

    public void delete(String projectId, String id) {
        if (!sources.deleteById(projectId, id)) {
            throw new TessaryException(IngestError.SOURCE_NOT_FOUND, id);
        }
    }
}
