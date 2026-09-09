// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.ingest.substrate.SubstrateSource;
import ai.tessary.ingest.upstream.FakeUpstreamSource;
import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.sources.SourceRow;
import ai.tessary.sources.SourceService;
import ai.tessary.storage.SpanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Construct an IngestionSource from a stored row. Only synthetic (non-network) sources remain. */
@Component
public class SourceFactory {

    // The shared strict @Primary mapper (JacksonConfig).
    private final ObjectMapper mapper;
    // The substrate spine, read by the synthetic "sdk" source so OTLP-ingested telemetry is gradable.
    private final SpanRepository spans;

    public SourceFactory(ObjectMapper mapper, SpanRepository spans) {
        this.mapper = mapper;
        this.spans = spans;
    }

    /** Open the source backing this row. Only synthetic (non-network) sources remain. */
    public IngestionSource open(SourceRow row) {
        // Synthetic in-memory source, no network, no credentials.
        if (FakeUpstreamSource.PROVIDER.equals(row.provider())) {
            return new FakeUpstreamSource();
        }
        // The "sdk" source reads the project's own substrate (no network, no credentials).
        if (SourceService.SDK_PROVIDER.equals(row.provider())) {
            return new SubstrateSource(row.projectId(), spans, mapper);
        }
        throw new TessaryException(IngestError.UNSUPPORTED_PROVIDER, row.provider());
    }
}
