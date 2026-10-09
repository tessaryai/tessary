// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.search.GlobalSearchService;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The traces search at volume, through the real repositories: 3,000,000 spans in a big project, 300,000 in a medium
 * one and 20,000 in a small one, all sharing the payload indexes, then every search the traces page, the sessions
 * list, the session expand and the palette can send.
 *
 * <p>Two setups, measured in order on the same data and the indexes {@code 0035} leaves:
 *
 * <ol>
 *   <li><b>index only</b>: every search lists every match (the walk is off). The shape the first attempt shipped,
 *       kept as the control.
 *   <li><b>this release</b>: the walk chosen from the project's match count.
 * </ol>
 *
 * <p>Each case runs once (the first read after the previous case) and then {@link #RUNS} times warm, reporting p50 and
 * p95, the shape the count chose and the rows. A case slower than {@link #REPEAT_CEILING_MS} on its first run is not
 * repeated. The seed also times what the migration costs: each index build, and inserting payloads with the old index
 * and then with the new one.
 *
 * <p>Pin Postgres to the reference box's share before the seed, or the numbers describe the laptop: {@code docker
 * update --cpus 1.25 --memory 3g --memory-swap 3g $(docker ps -q --filter label=org.testcontainers=true --filter
 * ancestor=pgvector/pgvector:pg16)}.
 *
 * <p>Manual, like the other {@code *PlanIT} tests: {@code TRACE_SEARCH_PLAN_IT=1 mvn -f backend/pom.xml -pl app -am
 * test -Dtest=TraceSearchQueryPlanIT -Dsurefire.failIfNoSpecifiedTests=false}. {@code TRACE_SEARCH_PLAN_SCALE=30}
 * divides the volume for a small Docker disk; the full scale needs about 30 GB free.
 */
@SpringBootTest(
        properties = {
            "tessary.ingest.substrate.resolvers-enabled=false",
            "tessary.ingest.substrate.rollup-enabled=false"
        })
@EnabledIfEnvironmentVariable(named = "TRACE_SEARCH_PLAN_IT", matches = ".+")
class TraceSearchQueryPlanIT {

    private static final Logger log = LoggerFactory.getLogger(TraceSearchQueryPlanIT.class);

    static final long SELECTIVE_BUDGET_MS = 500;
    static final long BROAD_BUDGET_MS = 1_500;
    static final int RUNS = 10;
    static final long REPEAT_CEILING_MS = 5_000;
    static final int PAGE = 50;

    private static final Instant END = Instant.parse("2026-09-30T00:00:00Z");
    private static final int SPANS_PER_TRACE = 10;
    private static final int TRACES_PER_SESSION = 5;
    private static final int CHUNKS = 10_000;
    private static final String OLD_INDEX = """
            CREATE INDEX ix_span_payload_fts ON span_payload USING gin (to_tsvector('simple'::regconfig,
                (("left"(COALESCE(input, ''::text), 100000) || ' '::text) || "left"(COALESCE(output, ''::text), 100000))))
            """;
    private static final String NEW_INDEX = """
            CREATE INDEX CONCURRENTLY ix_span_payload_project_fts ON span_payload USING gin
                (project_id, to_tsvector('simple', left(coalesce(input, ''), 100000) || ' '
                    || left(coalesce(output, ''), 100000)))
            """;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenants;

    @Autowired
    TraceV2Repository traces;

    @Autowired
    SessionRepository sessions;

    @Autowired
    SpanPayloadRepository payloads;

    @Autowired
    TraceSearchProperties search;

    @Autowired
    GlobalSearchService palette;

    @Autowired
    TraceFilters filters;

    @Autowired
    PlatformTransactionManager transactions;

    private final List<Row> rows = new ArrayList<>();
    private final List<String> costs = new ArrayList<>();
    private final List<Executable> budgets = new ArrayList<>();

    record Project(String id, long traces) {}

    /** {@code adversarial}: a case the design is known to lose; measured and reported, never held to a budget. */
    record Search(String label, String q, long budgetMs, boolean adversarial) {}

    @Test
    void traceSearchStaysInsideItsBudgetsAtVolume() {
        Project big = new Project(project("plan-big"), 300_000L / scale());
        Project medium = new Project(project("plan-medium"), Math.max(100, 30_000L / scale()));
        Project small = new Project(project("plan-small"), Math.max(50, 2_000L / scale()));
        seedAll(big, medium, small);

        List<Search> searches = List.of(
                new Search("rare word, 10 traces", "needlerare", SELECTIVE_BUDGET_MS, false),
                new Search("1% of traces", "needleone", SELECTIVE_BUDGET_MS, false),
                new Search("30% of traces", "needlethirty", BROAD_BUDGET_MS, false),
                new Search("absent word", "needleabsent", BROAD_BUDGET_MS, false),
                new Search("in every payload", "content", BROAD_BUDGET_MS, false),
                new Search("prefix of ~1,100 words", "w12", BROAD_BUDGET_MS, false),
                new Search("two words, one span", "pairleft pairright", SELECTIVE_BUDGET_MS, false),
                new Search("two words, two spans", "splitleft splitright", BROAD_BUDGET_MS, false),
                new Search("order id", "ORD-55812", SELECTIVE_BUDGET_MS, false),
                new Search("trace name substring", "agent-7", BROAD_BUDGET_MS, false),
                new Search("new word, after ANALYZE", "freshword", BROAD_BUDGET_MS, false),
                new Search("common only in old data", "oldword", BROAD_BUDGET_MS, true));

        search.setWalkThreshold(Integer.MAX_VALUE);
        search.setTimeoutMs(60_000);
        measure("index only", big, medium, small, searches);

        search.setWalkThreshold(50_000);
        search.setTimeoutMs(5_000);
        measure("this release", big, medium, small, searches);

        log.info("trace search costs:\n{}", String.join("\n", costs));
        log.info("trace search at volume:\n{}", table());
        assertAll(budgets);
    }

    private void measure(String arm, Project big, Project medium, Project small, List<Search> searches) {
        String week = END.minus(7, ChronoUnit.DAYS).toString();
        String month = END.minus(30, ChronoUnit.DAYS).toString();
        boolean judged = !arm.equals("index only");
        for (Search s : searches) {
            for (String[] range : List.of(
                    new String[] {"7 days", week}, new String[] {"30 days", month}, new String[] {"all time", null})) {
                time(
                        arm,
                        "first page, " + range[0],
                        s,
                        big,
                        judged,
                        () -> traces.list(big.id(), query(range[1], null, s.q()), null, PAGE, null, null, null));
            }
        }
        Search thirty = searches.get(2);
        List<TraceV2Repository.Summary> first =
                traces.list(big.id(), query(month, null, thirty.q()), null, PAGE, null, null, null);
        if (!first.isEmpty()) {
            TraceV2Repository.Summary last = first.get(first.size() - 1);
            time(
                    arm,
                    "page 2, 30 days",
                    thirty,
                    big,
                    judged,
                    () -> traces.list(
                            big.id(), query(month, null, thirty.q()), null, PAGE, null, last.startedAt(), last.id()));
        }
        for (Search s : List.of(searches.get(1), searches.get(3), searches.get(4))) {
            time(
                    arm,
                    "sessions list, 30 days",
                    s,
                    big,
                    judged,
                    () -> sessions.listByProject(big.id(), query(month, null, s.q()), PAGE, null, null));
        }
        String sessionOfAMatch = "sess-" + (big.traces() - 93) / TRACES_PER_SESSION;
        time(
                arm,
                "session expand",
                searches.get(1),
                big,
                judged,
                () -> traces.idsInSessionMatching(
                        big.id(),
                        sessionOfAMatch,
                        query(null, null, searches.get(1).q()),
                        100));
        Search hot = new Search("common in big, 2 traces here", "hotword", SELECTIVE_BUDGET_MS, false);
        time(
                arm,
                "medium project, all time",
                hot,
                medium,
                judged,
                () -> traces.list(medium.id(), query(null, null, hot.q()), null, PAGE, null, null, null));
        time(
                arm,
                "small project, all time",
                hot,
                small,
                judged,
                () -> traces.list(small.id(), query(null, null, hot.q()), null, PAGE, null, null, null));
        Search rare = searches.get(0);
        time(arm, "palette", rare, big, false, () -> palette.search(big.id(), rare.q()));
        explain(arm, big, query(month, null, "content"), "content, 30 days");
        explain(arm, big, query(month, null, "needlerare"), "rare word, 30 days");
        explain(arm, medium, query(null, null, "hotword"), "medium project, hotword");
    }

    /**
     * All three projects, then the old index as today's installs have it and the change {@code 0035} makes to it, with
     * an insert before and after that times each index's upkeep.
     */
    private void seedAll(Project big, Project medium, Project small) {
        jdbc.sql("DROP INDEX IF EXISTS ix_span_payload_fts").update();
        jdbc.sql("DROP INDEX IF EXISTS ix_span_payload_project_fts").update();
        seedChunks();
        long start = System.nanoTime();
        seed(big, 0, big.traces(), Map.of("hot", "g % 2 = 0", "old", "g < :n / 2"));
        seed(medium, 0, medium.traces(), Map.of("hot", "g < 2 AND j = 1", "old", "false"));
        seed(small, 0, small.traces(), Map.of("hot", "false", "old", "false"));
        costs.add(String.format(Locale.ROOT, "bulk seed, no payload index: %d s", seconds(start)));

        start = System.nanoTime();
        jdbc.sql(OLD_INDEX).update();
        costs.add(String.format(Locale.ROOT, "build ix_span_payload_fts: %d s", seconds(start)));
        long burst = Math.max(100, big.traces() / 30);
        start = System.nanoTime();
        seed(big, big.traces(), burst, Map.of("hot", "false", "old", "false"));
        costs.add(String.format(
                Locale.ROOT,
                "insert %d payloads, old index only: %d ms",
                burst * SPANS_PER_TRACE,
                (System.nanoTime() - start) / 1_000_000));

        start = System.nanoTime();
        jdbc.sql(NEW_INDEX).update();
        costs.add(String.format(Locale.ROOT, "build ix_span_payload_project_fts CONCURRENTLY: %d s", seconds(start)));
        costs.add(sizes());
        start = System.nanoTime();
        jdbc.sql("DROP INDEX CONCURRENTLY ix_span_payload_fts").update();
        costs.add(String.format(
                Locale.ROOT, "drop ix_span_payload_fts CONCURRENTLY: %d ms", (System.nanoTime() - start) / 1_000_000));
        for (String table : List.of("session", "trace", "span", "span_payload")) {
            jdbc.sql("VACUUM ANALYZE " + table).update();
        }
        start = System.nanoTime();
        seed(big, big.traces() + burst, burst, Map.of("hot", "false", "old", "false", "fresh", "true"));
        costs.add(String.format(
                Locale.ROOT,
                "insert %d payloads, new index only (freshword, after ANALYZE): %d ms",
                burst * SPANS_PER_TRACE,
                (System.nanoTime() - start) / 1_000_000));
        jdbc.sql("DROP TABLE plan_it_chunk").update();
        log.info("seeded:\n{}", String.join("\n", costs));
    }

    /**
     * Ten thousand ~500-byte text chunks over a skewed vocabulary of 50,000 words {@code w0…w49999}: a word's chance
     * falls with its number, as in real text. Payloads are built from these rather than word by word, so the seed is
     * minutes, not hours.
     */
    private void seedChunks() {
        jdbc.sql("CREATE TABLE plan_it_chunk (id int PRIMARY KEY, body text NOT NULL)")
                .update();
        jdbc.sql("""
                        INSERT INTO plan_it_chunk (id, body)
                        SELECT c, (SELECT string_agg('w' || floor(50000 * power(random(), 4))::int, ' ')
                                     FROM generate_series(1, 75) w WHERE c > -w)
                          FROM generate_series(0, :chunks - 1) c
                        """).param("chunks", CHUNKS).update();
    }

    /**
     * Traces {@code from} to {@code from + count} of one project, evenly over the 60 days before {@link #END} by trace
     * number {@code g} out of the project's {@code traces()}, five to a session, ten spans each, one in twenty errored.
     * Inputs are JSON, log-normal around three chunks (about 1.5 KB) with a tail past the 100 KB index cap, and one
     * trace in fifty carries a 50 KB system prompt; outputs are one chunk. Planted words, by {@code g}:
     * {@code needlerare} in 10 traces, {@code needleone} and {@code zq} in 1%, {@code needlethirty} in 30%,
     * {@code pairleft pairright} in one span of 1%, {@code splitleft} and {@code splitright} in two spans of 1%,
     * {@code ORD-55812} in 10 traces, and the per-project predicates {@code hot} ({@code hotword}), {@code old}
     * ({@code oldword}) and {@code fresh} ({@code freshword}).
     */
    private void seed(Project p, long from, long count, Map<String, String> words) {
        long n = p.traces();
        var params = new HashMap<String, Object>();
        params.put("pid", p.id());
        params.put("n", n);
        params.put("from", from);
        params.put("upto", from + count - 1);
        params.put("end", END.toString());
        params.put("step", (60L * 86_400_000L / n) + " milliseconds");
        params.put("perSession", TRACES_PER_SESSION);
        params.put("chunks", CHUNKS);
        params.put("rareEvery", Math.max(1, n / 10));
        String startedAt = "CAST(:end AS timestamptz) - (:n - g) * CAST(:step AS interval)";
        jdbc.sql("""
                        INSERT INTO session (project_id, id, user_id, started_at, last_activity_at, event_ts)
                        SELECT :pid, 'sess-' || s, 'user-' || (s % 5000),
                               CAST(:end AS timestamptz) - (:n - s * :perSession) * CAST(:step AS interval),
                               CAST(:end AS timestamptz)
                                   - (:n - s * :perSession - :perSession + 1) * CAST(:step AS interval),
                               CAST(:end AS timestamptz)
                          FROM generate_series(:from / :perSession, :upto / :perSession) s
                        ON CONFLICT DO NOTHING
                        """).params(params).update();
        jdbc.sql("""
                        INSERT INTO trace (project_id, id, session_id, name, user_id, started_at, ended_at, event_ts,
                                           span_count, error_count, is_settled, has_root_span)
                        SELECT :pid, 'tr-' || g, 'sess-' || (g / :perSession), 'agent-' || (g % 20),
                               'user-' || ((g / :perSession) % 5000), STARTED, STARTED + interval '2 seconds',
                               CAST(:end AS timestamptz), 10, CASE WHEN g % 20 = 0 THEN 1 ELSE 0 END, true, true
                          FROM generate_series(:from, :upto) g
                        """.replace("STARTED", startedAt)).params(params).update();
        jdbc.sql("""
                        INSERT INTO span (project_id, trace_id, id, parent_span_id, session_id, kind, name,
                                          is_logical_root, started_at, ended_at, event_ts, correlation_state,
                                          path_state)
                        SELECT t.project_id, t.id, 'sp-' || j, CASE WHEN j = 1 THEN NULL ELSE 'sp-1' END,
                               t.session_id, 'llm', 'step-' || j, j = 1, t.started_at, t.ended_at, t.event_ts,
                               'done', 'resolved'
                          FROM generate_series(:from, :upto) g
                          JOIN trace t ON t.project_id = :pid AND t.id = 'tr-' || g
                         CROSS JOIN generate_series(1, 10) j
                        """).params(params).update();
        jdbc.sql("""
                        INSERT INTO span_payload (project_id, trace_id, span_id, input, output, event_ts)
                        SELECT :pid, 'tr-' || g, 'sp-' || j,
                               '{"role":"user","content":"'
                                 || CASE WHEN j = 1 AND g % 50 = 0 THEN (SELECT string_agg(c.body, ' ')
                                      FROM plan_it_chunk c WHERE c.id BETWEEN 100 AND 199) || ' ' ELSE '' END
                                 || (SELECT string_agg(c.body, ' ')
                                       FROM generate_series(1, k) i
                                       JOIN plan_it_chunk c
                                         ON c.id = (g * 7919 + j * 104729 + i::bigint * 15485863) % :chunks)
                                 || CASE WHEN j = 5 AND g % :rareEvery = 0 THEN ' needlerare' ELSE '' END
                                 || CASE WHEN j = 3 AND g % 100 = 7 THEN ' needleone zq' ELSE '' END
                                 || CASE WHEN j = 2 AND g % 10 IN (1, 2, 3) THEN ' needlethirty' ELSE '' END
                                 || CASE WHEN j = 4 AND g % 100 = 11 THEN ' pairleft pairright' ELSE '' END
                                 || CASE WHEN j = 1 AND g % 100 = 13 THEN ' splitleft' ELSE '' END
                                 || CASE WHEN j = 6 AND g % 100 = 13 THEN ' splitright' ELSE '' END
                                 || CASE WHEN j = 7 AND g % :rareEvery = 3 THEN ' {"order_id":"ORD-55812"}' ELSE '' END
                                 || CASE WHEN HOT THEN ' hotword' ELSE '' END
                                 || CASE WHEN OLD THEN ' oldword' ELSE '' END
                                 || CASE WHEN FRESH THEN ' freshword' ELSE '' END
                                 || '"}',
                               '{"content":"' || (SELECT c.body FROM plan_it_chunk c WHERE c.id = (g * 31 + j) % :chunks)
                                 || '"}',
                               CAST(:end AS timestamptz)
                          FROM generate_series(:from, :upto) g
                         CROSS JOIN generate_series(1, 10) j
                         CROSS JOIN LATERAL (SELECT greatest(1, least(400,
                                round(exp(ln(3) + 1.2 * random_normal()))))::int AS k WHERE g > -j) sz
                        """.replace("HOT", words.get("hot"))
                        .replace("OLD", words.get("old"))
                        .replace("FRESH", words.getOrDefault("fresh", "false")))
                .params(params)
                .update();
    }

    private void time(String arm, String shape, Search s, Project p, boolean judged, Supplier<List<?>> read) {
        String words = payloads.prefixQuery(s.q());
        String chosen = words == null || search.getWalkThreshold() == Integer.MAX_VALUE
                ? (words == null ? "id lane" : "index")
                : payloads.countMatches(p.id(), words, search.getWalkThreshold()) >= search.getWalkThreshold()
                        ? "walk"
                        : "index";
        long start = System.nanoTime();
        int hits;
        try {
            hits = read.get().size();
        } catch (TessaryException e) {
            if (e.error() != QueryError.SEARCH_TOO_BROAD) {
                throw e;
            }
            double ms = (System.nanoTime() - start) / 1e6;
            rows.add(new Row(arm, shape, s.label(), chosen, -1, ms, Double.NaN, Double.NaN, s.budgetMs()));
            if (judged && !s.adversarial()) {
                budgets.add(() -> assertTrue(false, arm + " / " + shape + " / " + s.label() + ": timed out"));
            }
            return;
        }
        double firstMs = (System.nanoTime() - start) / 1e6;
        if (firstMs > REPEAT_CEILING_MS) {
            rows.add(new Row(arm, shape, s.label(), chosen, hits, firstMs, Double.NaN, Double.NaN, s.budgetMs()));
            if (judged && !s.adversarial()) {
                budgets.add(
                        () -> assertTrue(false, arm + " / " + shape + " / " + s.label() + ": first run " + firstMs));
            }
            return;
        }
        double[] warm = new double[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            int again = read.get().size();
            warm[i] = (System.nanoTime() - t) / 1e6;
            if (again != hits) {
                throw new AssertionError(shape + " / " + s.label() + ": " + hits + " rows, then " + again);
            }
        }
        Arrays.sort(warm);
        double p50 = warm[RUNS / 2];
        double p95 = warm[(int) Math.ceil(RUNS * 0.95) - 1];
        rows.add(new Row(arm, shape, s.label(), chosen, hits, firstMs, p50, p95, s.budgetMs()));
        if (judged && !shape.equals("palette") && !s.adversarial()) {
            budgets.add(() -> assertTrue(
                    p95 <= s.budgetMs(),
                    String.format(
                            Locale.ROOT,
                            "%s / %s / %s: warm p95 %.0f ms over %d ms",
                            arm,
                            shape,
                            s.label(),
                            p95,
                            s.budgetMs())));
        }
    }

    /** One first-page plan, logged with its buffers, through the WHERE that {@link TraceFilters} builds. */
    private void explain(String arm, Project p, TraceV2Repository.TraceQuery query, String label) {
        var params = new HashMap<String, Object>();
        params.put("pid", p.id());
        params.put("limit", PAGE);
        var where = new StringBuilder("WHERE t.project_id = :pid AND NOT t.is_deleted");
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        try {
            String plan = tx.execute(status -> {
                filters.append(p.id(), where, params, query);
                return String.join(
                        "\n",
                        jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) SELECT t.id FROM trace t " + where
                                        + " ORDER BY t.started_at DESC, t.id DESC LIMIT :limit")
                                .params(params)
                                .query(String.class)
                                .list());
            });
            log.info("plan, {}, {}:\n{}", arm, label, plan);
        } catch (RuntimeException e) {
            log.info("plan, {}, {}: {}", arm, label, e.getMessage());
        }
    }

    private String sizes() {
        return jdbc.sql("""
                        SELECT string_agg(relname || ' ' || pg_size_pretty(pg_total_relation_size(oid)), ', '
                                          ORDER BY relname)
                          FROM pg_class WHERE relname IN ('trace', 'span', 'span_payload', 'ix_span_payload_fts',
                                                         'ix_span_payload_project_fts')
                        """).query(String.class).single();
    }

    private String table() {
        var out = new StringBuilder(
                "| Setup | Shape | Search | Chosen | Rows | First (ms) | Warm p50 (ms) | Warm p95 (ms) | Budget |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            out.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %.0f | %s | %s | %d |%n",
                    r.arm(),
                    r.shape(),
                    r.search(),
                    r.chosen(),
                    r.hits() < 0 ? "timed out" : Integer.toString(r.hits()),
                    r.firstMs(),
                    Double.isNaN(r.p50()) ? "-" : String.format(Locale.ROOT, "%.0f", r.p50()),
                    Double.isNaN(r.p95()) ? "-" : String.format(Locale.ROOT, "%.0f", r.p95()),
                    r.budgetMs()));
        }
        return out.toString();
    }

    private String project(String slug) {
        return TenantFixture.bootstrap(tenants, slug).project().id();
    }

    private static TraceV2Repository.TraceQuery query(@Nullable String from, @Nullable String to, String q) {
        return new TraceV2Repository.TraceQuery(null, null, null, null, from, to, null, q, null);
    }

    private static long seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }

    /** Divides every volume. The plan shapes hold at any scale; the budgets are for the full one. */
    private static long scale() {
        String raw = System.getenv("TRACE_SEARCH_PLAN_SCALE");
        return raw == null || raw.isBlank() ? 1 : Math.max(1, Long.parseLong(raw.trim()));
    }

    private record Row(
            String arm,
            String shape,
            String search,
            String chosen,
            int hits,
            double firstMs,
            double p50,
            double p95,
            long budgetMs) {}
}
