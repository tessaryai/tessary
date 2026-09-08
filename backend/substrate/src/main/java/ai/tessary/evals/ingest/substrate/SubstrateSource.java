// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.substrate;

import ai.tessary.evals.ingest.ImportFilter;
import ai.tessary.evals.ingest.IngestionSource;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.sources.SourceService;
import ai.tessary.evals.storage.SpanRepository;
import ai.tessary.evals.storage.SpanRepository.SpanEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;

/**
 * The {@link IngestionSource} for the {@code sdk} provider: it reads a project's own substrate
 * ({@code span} joined to {@code span_payload}) so telemetry ingested through the OTLP receiver becomes a
 * selectable, gradable dataset source — completing the ingest → source → live-dataset → run chain. There is
 * no network and no credential: it is opened directly from the per-project singleton {@link SourceService}
 * row (see {@link SourceService#ensureSdkSource}).
 *
 * <p><b>Identity mapping — the producer's own ids, all the way through.</b> A {@link RawEntry} exposes
 * {@code traceId} ← the producer trace id and {@code sourceExternalId} ← the composite handle
 * {@code "<trace_id>:<span_id>"}. The handle exists because a span id is unique only inside its trace: a
 * {@code sourceExternalId} is the token a caller hands back to {@link #fetchOne}, so it has to carry the
 * whole key or the point read is a guess. {@code parentId} is the parent's handle in the same trace, so a
 * child's {@code parentId} still equals its parent's {@code sourceExternalId} and the span tree holds.
 *
 * <p>This is a fidelity win downstream: the export mapper writes {@code context.trace_id} /
 * {@code context.span_id} verbatim from these fields, so a trace exported for the plugin now carries the
 * ids the producer actually emitted rather than surrogates we minted, and the plugin's multi-turn grouping
 * groups on the real trace.
 *
 * <p><b>Full I/O.</b> {@link #returnsFullIoOnFetch()} is {@code true}: the substrate stores untruncated
 * input/output, so a run maps directly from one paginated pass and never re-fetches per entry. A span whose
 * payload has aged out reads as an entry with null text rather than disappearing — the step still happened.
 *
 * <p><b>Pre-resolved call site.</b> Each {@code RawEntry} carries the span's already-resolved
 * {@code call_site_id}; downstream grading honors it directly, so an sdk-source read grades by
 * the call site the substrate resolved at ingest rather than re-deriving it through per-source mappings.
 */
public final class SubstrateSource implements IngestionSource {

    /** Page size for the lazy {@link #fetch} walk over the project's spans. */
    private static final int PAGE_SIZE = 500;

    private final String projectId;
    private final SpanRepository spans;
    private final ObjectMapper mapper;

    public SubstrateSource(String projectId, SpanRepository spans, ObjectMapper mapper) {
        this.projectId = projectId;
        this.spans = spans;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return SourceService.SDK_PROVIDER;
    }

    @Override
    public Stream<RawEntry> fetch(ImportFilter filter) {
        String from = lowerBound(filter);
        String to = filter == null ? null : filter.toTimestamp();
        Iterator<RawEntry> it = new Iterator<>() {
            private int offset = 0;
            private boolean exhausted = false;
            private Iterator<RawEntry> page = Collections.emptyIterator();

            private void advance() {
                while (!page.hasNext() && !exhausted) {
                    List<SpanEntry> rows = spans.listEntriesByProject(projectId, from, to, PAGE_SIZE, offset);
                    offset += rows.size();
                    exhausted = rows.size() < PAGE_SIZE;
                    page = rows.stream().map(SubstrateSource.this::toRawEntry).iterator();
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return page.hasNext();
            }

            @Override
            public RawEntry next() {
                advance();
                return page.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    /**
     * One entry by its {@code sourceExternalId} — the composite {@code "<trace_id>:<span_id>"} handle this
     * source minted. A handle that does not carry both halves names no span and is rejected as such, with
     * the expected shape in the message: the failure mode this replaces is a bare span id resolving against
     * whichever trace happened to contain a span with that id, which is a wrong answer rather than an error.
     */
    @Override
    public RawEntry fetchOne(String externalId, ImportFilter context) {
        int sep = externalId.indexOf(':');
        if (sep <= 0 || sep == externalId.length() - 1) {
            throw new IllegalArgumentException(
                    "sdk source: span handle must be \"<trace_id>:<span_id>\", got " + externalId);
        }
        String traceId = externalId.substring(0, sep);
        String spanId = externalId.substring(sep + 1);
        return spans.findEntry(projectId, traceId, spanId)
                .map(this::toRawEntry)
                .orElseThrow(() -> new IllegalArgumentException("sdk source: unknown span " + externalId));
    }

    @Override
    public List<RawEntry> fetchTrace(String traceId, ImportFilter context) {
        return spans.listEntriesByTrace(projectId, traceId).stream()
                .map(this::toRawEntry)
                .toList();
    }

    @Override
    public List<RawEntry> fetchRecentByCallSite(String callSiteId, int limit, @Nullable Instant notBefore) {
        // Tag-as-the-model: spans already carry the call site pre-resolved at ingest, so
        // grounding reads them directly by call_site_id (indexed) — no source-mapping re-derivation.
        return spans.recentEntriesByCallSite(projectId, callSiteId, limit, notBefore).stream()
                .map(this::toRawEntry)
                .toList();
    }

    @Override
    public boolean returnsFullIoOnFetch() {
        return true;
    }

    private RawEntry toRawEntry(SpanEntry s) {
        String parentSpanId = s.parentSpanId();
        return new RawEntry(
                s.handle(), // sourceExternalId — "<trace_id>:<span_id>" (fetchOne = findEntry)
                null, // sourceUrl — not stored in the substrate
                s.name(),
                s.input(),
                s.output(),
                s.providedModelName(),
                parseMetadata(s.attributes()),
                // parentId — the parent's handle in the same trace, so it equals the parent's
                // sourceExternalId and the tree holds.
                parentSpanId == null ? null : s.traceId() + ':' + parentSpanId,
                s.traceId(), // traceId — the producer's trace id (fetchTrace = listEntriesByTrace)
                s.startedAt(),
                s.kind(), // operationKind — already normalized at ingest
                s.endedAt(),
                null, // inputMessagesJson — materialized at ingest, not re-derived here
                null, // outputMessagesJson
                s.callSiteId()); // pre-resolved call site
    }

    private Map<String, Object> parseMetadata(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // A malformed metadata blob must not sink the whole pull — drop to empty metadata for this entry.
            return Map.of();
        }
    }

    /**
     * Inclusive lower bound on {@code started_at}: an explicit {@link ImportFilter#fromTimestamp()} wins,
     * else a relative {@link ImportFilter#lookbackHours()} is resolved against now, else unbounded.
     */
    private static @Nullable String lowerBound(@Nullable ImportFilter filter) {
        if (filter == null) {
            return null;
        }
        if (filter.fromTimestamp() != null) {
            return filter.fromTimestamp();
        }
        Integer lookback = filter.lookbackHours();
        if (lookback != null) {
            return Instant.now().minus(Duration.ofHours(lookback)).toString();
        }
        return null;
    }
}
