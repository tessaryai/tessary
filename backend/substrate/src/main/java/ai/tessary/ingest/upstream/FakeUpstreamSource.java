// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.upstream;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.ImportFilter;
import ai.tessary.ingest.IngestionSource;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.Scope;
import ai.tessary.ingest.ScopeKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A deterministic in-memory {@link IngestionSource} backing the {@code "fake"} provider.
 * It exists so the on-demand selective-pull seam can be exercised end-to-end — through
 * {@link ai.tessary.ingest.SourceFactory}, the pull runner, the canonical normalization, and the
 * idempotent substrate write path — with no network and no real vendor account.
 *
 * <p>It is NOT test-only: like {@code SourceService.UPLOAD_PROVIDER}, it is a first-class synthetic
 * provider so acceptance tests open it exactly as a real source is opened. The corpus is fixed and
 * deterministic so a test can assert exact session/turn/observation counts and verify that selecting a
 * subset (by trace id or session id) lands only that subset.
 *
 * <p><b>Full I/O contract.</b> {@link #returnsFullIoOnFetch()} is {@code true}: {@link #fetch} already
 * returns untruncated input/output (the corpus is in memory), so the pull runner maps directly from one
 * pass and never needs {@code fetchOne} — honoring the project rule that trace I/O is never truncated.
 */
public final class FakeUpstreamSource implements IngestionSource {

    /** The synthetic provider tag. Registered in SourceFactory + SourceService SUPPORTED. */
    public static final String PROVIDER = "fake";

    /** Stable corpus identifiers so tests can select precise slices. */
    public static final List<String> SESSION_IDS = List.of("fake-sess-1", "fake-sess-2", "fake-sess-3");

    /** The discoverable dataset handle whose {@link #fetchSavedView} narrows to a known trace subset. */
    public static final String DATASET = "fake-dataset";

    /** The trace ids the {@link #DATASET} resolves to — a strict subset of the corpus (the first trace). */
    public static final List<String> DATASET_TRACE_IDS = List.of("fake-trace-1");

    private static final String T0 = Instant.parse("2026-02-01T00:00:00Z").toString();

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public List<Scope> enumerateScopes(ScopeKind kind) {
        return switch (kind) {
            case PROJECT ->
                List.of(new Scope(ScopeKind.PROJECT, "fake-project", "Fake Project", (long) SESSION_IDS.size()));
            case DATASET -> List.of(new Scope(ScopeKind.DATASET, "fake-dataset", "Fake Dataset", 1L));
            case SAVED_VIEW -> List.of(new Scope(ScopeKind.SAVED_VIEW, "errors-view", "Errors only", null));
        };
    }

    /**
     * The full deterministic corpus: one session per {@link #SESSION_IDS} entry, each a single trace
     * (one agent span + one tool span) — so the whole corpus is 3 sessions / 3 turns / 3 traces /
     * 6 observations / 3 tool calls. The pull runner applies any {@link ai.tessary.ingest.Selection}
     * id-list narrowing on top; this method honors only the canonical {@link ImportFilter}.
     */
    @Override
    public Stream<RawEntry> fetch(ImportFilter filter) {
        List<RawEntry> out = new ArrayList<>();
        for (int i = 0; i < SESSION_IDS.size(); i++) {
            String session = SESSION_IDS.get(i);
            String traceId = "fake-trace-" + (i + 1);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(GenAiAttributes.SESSION_ID, session);
            meta.put("user.id", "fake-user-" + (i + 1));
            // Agent root span — full I/O, never truncated.
            out.add(new RawEntry(
                    traceId + "-agent",
                    null,
                    "fake-agent",
                    "user asks question " + (i + 1),
                    "agent answers question " + (i + 1),
                    "fake-model",
                    Map.copyOf(meta),
                    null,
                    traceId,
                    T0,
                    KindNormalizer.AGENT));
            // Tool child span.
            out.add(new RawEntry(
                    traceId + "-tool",
                    null,
                    "fake-search",
                    "{\"q\":\"" + (i + 1) + "\"}",
                    "{\"hits\":" + (i + 1) + "}",
                    null,
                    Map.copyOf(meta),
                    traceId + "-agent",
                    traceId,
                    T0,
                    KindNormalizer.TOOL));
        }
        // Honor the canonical name filter if the caller set one (the runner relies on the SPI contract).
        String name = filter == null ? null : filter.name();
        if (name != null && !name.isBlank()) {
            out.removeIf(r -> {
                String n = r.name();
                return n == null || !n.contains(name);
            });
        }
        return out.stream();
    }

    /**
     * Dataset-scoped narrowing: the {@link #DATASET} handle resolves to
     * {@link #DATASET_TRACE_IDS} — a strict subset of the corpus — so a dataset pull lands only those
     * traces, mirroring how {@code LangfuseSource} resolves a dataset's items to trace ids and re-fetches
     * exactly those. An unknown handle yields an empty stream (never a fallback to the whole corpus).
     */
    @Override
    public Stream<RawEntry> fetchSavedView(String savedView, ImportFilter context) {
        if (!DATASET.equals(savedView)) return Stream.empty();
        try (Stream<RawEntry> s = fetch(context)) {
            return s.filter(r -> r.traceId() != null && DATASET_TRACE_IDS.contains(r.traceId())).toList().stream();
        }
    }

    @Override
    public RawEntry fetchOne(String externalId, ImportFilter context) {
        try (Stream<RawEntry> s = fetch(context)) {
            return s.filter(r -> externalId.equals(r.sourceExternalId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("fake source: unknown id " + externalId));
        }
    }

    @Override
    public boolean returnsFullIoOnFetch() {
        return true;
    }
}
