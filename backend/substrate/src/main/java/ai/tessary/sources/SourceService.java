// SPDX-License-Identifier: Apache-2.0
package ai.tessary.sources;

import ai.tessary.crypto.SecretBox;
import ai.tessary.ingest.UrlGuard;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sources.SourceDtos.CreateSourceRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SourceService {

    /** Provider tag for the synthetic in-memory upstream source backing the pull seam. */
    public static final String FAKE_PROVIDER = ai.tessary.ingest.upstream.FakeUpstreamSource.PROVIDER;

    /**
     * Provider tag for the synthetic source backing data ingested through the OTLP receiver.
     * It is never network-fetched: its {@link ai.tessary.ingest.IngestionSource} reads the project's
     * own substrate ({@code observation} table) so ingested telemetry becomes a selectable dataset
     * source. One per project (see {@link #ensureSdkSource}).
     */
    public static final String SDK_PROVIDER = "sdk";

    private static final String SDK_SOURCE_NAME = "Tessary SDK";

    /** Sentinel baseUrl for synthetic, non-network sources — satisfies the {@code @NotBlank} contract. */
    private static final String SDK_BASE_URL = "tessary://sdk";

    private static final Set<String> SUPPORTED = Set.of(FAKE_PROVIDER, SDK_PROVIDER);

    /**
     * Providers that are synthetic — never network-fetched — so they bypass the SSRF baseUrl guard and
     * the credential-seal (they carry no secret). The {@code upload} sink, the {@code fake} upstream
     * source, and the {@code sdk} substrate source are all opened directly from a {@link SourceRow}
     * with no HTTP call.
     */
    private static final Set<String> NON_NETWORK_PROVIDERS = Set.of(FAKE_PROVIDER, SDK_PROVIDER);

    /** Provider tag for the synthetic source backing uploaded JSONL traces. Never network-fetched. */
    public static final String UPLOAD_PROVIDER = "upload";

    private static final String UPLOAD_SOURCE_NAME = "Uploaded traces";

    private final SourceRepository sources;
    private final SecretBox secretBox;
    private final ObjectMapper mapper;

    public SourceService(SourceRepository sources, SecretBox secretBox, ObjectMapper mapper) {
        this.sources = sources;
        this.secretBox = secretBox;
        this.mapper = mapper;
    }

    public List<SourceRow> list(String projectId) {
        return sources.findAllForProject(projectId);
    }

    public SourceRow get(String projectId, String id) {
        return sources.findById(projectId, id)
                .orElseThrow(() -> new TessaryException(IngestError.SOURCE_NOT_FOUND, id));
    }

    /** The vendor of one source (unscoped), for stamping run/verdict provenance. Empty when the
     *  source row is gone. Cheap projection — does not load or decrypt credentials. */
    public java.util.Optional<String> findProvider(String id) {
        return sources.findProvider(id);
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
        boolean network = !NON_NETWORK_PROVIDERS.contains(req.provider());
        String enc;
        if (network) {
            if (!secretBox.isConfigured()) {
                throw new TessaryException(IngestError.MISSING_SECRET_KEY);
            }
            // SSRF guard. Resolves DNS now and rejects internal/loopback/IMDS
            // addresses. The persisted URL is the user's original string; every
            // subsequent fetch re-validates so DNS-rebinding mid-rotation is
            // caught on the next call.
            UrlGuard.requirePublicHttp(req.baseUrl());
            String credsJson;
            try {
                credsJson = mapper.writeValueAsString(req.credentials() == null ? Map.of() : req.credentials());
            } catch (Exception e) {
                throw new IllegalArgumentException("could not serialize credentials", e);
            }
            enc = secretBox.seal(credsJson);
        } else {
            // Synthetic source (fake upstream): no network fetch, no SSRF guard, no credential seal.
            enc = "";
        }
        String now = Instant.now().toString();
        SourceRow row = new SourceRow(
                UUID.randomUUID().toString(), projectId, req.provider(), req.name(), req.baseUrl(), enc, now, now);
        sources.insert(row);
        return row;
    }

    /**
     * Returns the project's reusable "upload" source, creating it on first use.
     * This source is never network-fetched (see {@link ai.tessary.ingest.SourceFactory});
     * it exists only to satisfy the {@code run.source_id} FK for uploaded JSONL
     * traces, so it carries no credentials and bypasses the user-facing
     * provider whitelist + SSRF guard in {@link #create}.
     */
    public SourceRow ensureUploadSource(String projectId) {
        // Key on the synthetic "upload" provider, not the display name — so a
        // user-created source that happens to be named "Uploaded traces" can't
        // collide with (or be mistaken for) the upload sink.
        return sources.findAllForProject(projectId).stream()
                .filter(s -> UPLOAD_PROVIDER.equals(s.provider()))
                .findFirst()
                .orElseGet(() -> {
                    String now = Instant.now().toString();
                    SourceRow row = new SourceRow(
                            UUID.randomUUID().toString(),
                            projectId,
                            UPLOAD_PROVIDER,
                            UPLOAD_SOURCE_NAME,
                            "file://upload",
                            "",
                            now,
                            now);
                    sources.insert(row);
                    return row;
                });
    }

    /**
     * Returns the project's reusable "sdk" source, creating it on first use — the substrate-backed source
     * that makes OTLP-ingested telemetry a selectable dataset source. Like
     * {@link #ensureUploadSource} it is keyed on the synthetic provider (not the display name) so it is a
     * per-project singleton, carries no credentials, and bypasses the network whitelist + SSRF guard.
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

    /** Decrypts + parses the credential JSON for runtime use. */
    public Map<String, String> openCredentials(SourceRow row) {
        // Non-network providers carry no secret and are persisted with an empty seal
        // (see create), so decrypting it would
        // throw "ciphertext too short". Short-circuit before any secretBox call — mirroring the
        // network guard in create and the pre-guard in SourceFactory.open — so every caller
        // (via SourceFactory.open on the pull path) is covered, not just one call site. A fake source
        // is gradable even with no secret key configured.
        if (NON_NETWORK_PROVIDERS.contains(row.provider())) {
            return Map.of();
        }
        if (!secretBox.isConfigured()) {
            throw new TessaryException(IngestError.MISSING_SECRET_KEY);
        }
        String json = secretBox.open(row.credentialsEnc());
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = mapper.readValue(json, Map.class);
            return Map.copyOf(map);
        } catch (Exception e) {
            throw new IllegalStateException("source " + row.id() + ": malformed credentials JSON", e);
        }
    }
}
