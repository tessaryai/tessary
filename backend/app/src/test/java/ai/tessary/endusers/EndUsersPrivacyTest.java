// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.job.EndUserHistoryHorizon;
import ai.tessary.endusers.job.EndUsersStage;
import ai.tessary.endusers.job.FoldStage;
import ai.tessary.endusers.job.HitsStage;
import ai.tessary.endusers.job.HorizonSweepStage;
import ai.tessary.endusers.job.JudgeStage;
import ai.tessary.endusers.job.LearnStage;
import ai.tessary.endusers.job.ReconcileStage;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.read.EndUserController;
import ai.tessary.endusers.read.EndUserDtos.EndUserEraseRequest;
import ai.tessary.retention.RetentionRepository;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TenantFixture.Setup;
import ai.tessary.web.ApiResponse;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Plan 3.7: a user ID's text and its salted key never reach a log line, and the text is kept in one column only. The
 * project is seeded with 50 known ID texts and driven through the hourly hit sweep and every stage of the daily job,
 * through an erasure, through a sweep that reads the raw IDs of the kept traces to clear texts, and through a horizon
 * sweep that deletes every fact. Log lines are read from the root logger, so
 * a line of any class counts, with the end-user package raised to TRACE, so a line at any level counts.
 */
@SpringBootTest
record EndUsersPrivacyTest(
        TenantService tenants,
        JdbcClient jdbc,
        HitsStage hits,
        FoldStage fold,
        ReconcileStage reconcile,
        LearnStage learn,
        JudgeStage judge,
        HorizonSweepStage sweep,
        EndUserController controller,
        EndUserHistoryHorizon horizon,
        RetentionRepository retention) {

    private static final String END_USERS_PACKAGE = "ai.tessary.endusers";
    private static final String TRACE_NAME = "chat.turn";
    private static final int IDS = 50;
    private static final int TTL_DAYS = 25;
    private static final HexFormat HEX = HexFormat.of();
    private static final Set<String> EVERY_STEP = Set.of(
            "endusers.hits.sweep",
            "endusers.fold.day",
            "endusers.fold",
            "endusers.hits.reconcile",
            "endusers.learn",
            "endusers.judge.stage",
            "endusers.judge",
            "endusers.erase",
            "endusers.sweep");

    /**
     * The bug: a stage, the erasure or a debug line writes a user ID's text or its key (as hex, Base64 or raw bytes)
     * into a log line, its structured fields or an exception message.
     */
    @Test
    void logs_holdNoUserIdTextAndNoUserKey() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-privacy-logs");
        String pid = s.project().id();
        int d = jobDay();
        List<String> texts = seed(pid, d);

        RootCapture capture = RootCapture.start();
        Map<String, String> keysByText;
        PageState judged;
        int clearedTexts;
        try {
            hits.run(pid, d);
            daily(pid, d);
            keysByText = storedKeys(pid);
            judged = data(controller.overview(owner(s), org(s), proj(s))).pageState();
            controller.erase(owner(s), org(s), proj(s), new EndUserEraseRequest(texts.getFirst()));
            ttl(pid, TTL_DAYS);
            retention.deleteTraces(
                    pid, Instant.ofEpochSecond((d + 1L - TTL_DAYS) * 86_400L).toString(), 10_000);
            sweep.run(pid, d);
            clearedTexts = IDS - 1 - storedKeys(pid).size();
            sweep.run(pid, d + horizon.days(pid).orElseThrow());
        } finally {
            capture.close();
        }

        byte[] salt = salt(pid);
        Map<String, String> expectedKeys = new TreeMap<>();
        List<String> hexKeys = new ArrayList<>();
        List<String> base64Keys = new ArrayList<>();
        for (String text : texts) {
            byte[] key = EndUserKeys.userKey(salt, text);
            expectedKeys.put(text, HEX.formatHex(key));
            hexKeys.add(HEX.formatHex(key));
            base64Keys.add(Base64.getEncoder().encodeToString(key));
        }
        Set<String> seen = new TreeSet<>();
        List<String> offenders = new ArrayList<>();
        for (ILoggingEvent e : capture.events()) {
            String event = field(e, "event");
            if (event != null && pid.equals(field(e, "project"))) seen.add(event);
            String line = rendered(e);
            String lower = line.toLowerCase(Locale.ROOT);
            for (int i = 0; i < texts.size(); i++) {
                String where = (event == null ? e.getLoggerName() : event) + " id " + (i + 1);
                if (lower.contains(texts.get(i).toLowerCase(Locale.ROOT))) offenders.add(where + " text");
                if (lower.contains(hexKeys.get(i))) offenders.add(where + " hex key");
                if (line.contains(base64Keys.get(i))) offenders.add(where + " base64 key");
            }
        }

        assertAll(
                () -> assertEquals(expectedKeys, keysByText, "the fold keyed the 50 seeded IDs, so the probe is real"),
                () -> assertEquals(PageState.OK, judged, "the judge ran every stage"),
                () -> assertEquals(
                        6,
                        clearedTexts,
                        "IDs 39 to 41, 43 and 45 to 48 have no trace on or after the TTL cut day; retention keeps the"
                                + " member-ref traces of IDs 39 and 40, so the sweep read their raw IDs and cleared the"
                                + " other six texts"),
                () -> assertTrue(seen.containsAll(EVERY_STEP), "every step logged for this project; saw " + seen),
                () -> assertEquals(List.of(), offenders));
    }

    /**
     * The bug: a fact, issue, snapshot or state row keeps a user ID's text, so the horizon sweep's clearing of {@code
     * end_user.id_text} does not remove the text, and an erasure by key misses it.
     */
    @Test
    void factRows_holdNoIdText() {
        Setup s = TenantFixture.bootstrap(tenants, "eu-privacy-rows");
        String pid = s.project().id();
        int d = jobDay();
        List<String> texts = seed(pid, d);
        hits.run(pid, d);
        daily(pid, d);

        Map<String, Integer> rowsByTable = new TreeMap<>();
        Map<String, List<Integer>> textsByTable = new TreeMap<>();
        for (String table : endUserTables()) {
            String row = table.equals("end_user") ? "to_jsonb(t) - 'id_text'" : "to_jsonb(t)";
            List<String> rows = jdbc.sql("SELECT (" + row + ")::text FROM " + table + " t WHERE project_id = :pid")
                    .param("pid", pid)
                    .query(String.class)
                    .list();
            rowsByTable.put(table, rows.size());
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                if (rows.stream().anyMatch(r -> r.contains(text))) {
                    textsByTable.computeIfAbsent(table, t -> new ArrayList<>()).add(i + 1);
                }
            }
        }
        Set<String> stored = new TreeSet<>(jdbc.sql("SELECT id_text FROM end_user WHERE project_id = :pid")
                .param("pid", pid)
                .query(String.class)
                .list());

        assertAll(
                () -> assertEquals(new TreeSet<>(texts), stored, "the text is kept in end_user.id_text"),
                () -> assertTrue(rowsByTable.get("end_user_day") > 0, "the fold wrote day rows"),
                () -> assertTrue(rowsByTable.get("end_user_hit") > 0, "the sweep wrote hit rows"),
                () -> assertTrue(rowsByTable.get("end_user_issue_hit") > 0, "the judge wrote issue hit rows"),
                () -> assertTrue(rowsByTable.get("end_user_snapshot") > 0, "the judge wrote a snapshot"),
                () -> assertEquals(Map.of(), textsByTable, "table to the numbers of the seeded IDs found in it"));
    }

    /**
     * 50 user IDs in five account clusters of ten, one shape for all. ID {@code i} starts {@code 70 - i % 10} days
     * before the job's day and has a trace every 2 or 3 days; IDs 1 to 38 are still active, IDs 39 to 50 stopped 22 to
     * 28 days before it. Every fourth day has a trace with no user ID. One tool-error finding in a case has witness refs
     * on the second trace of IDs 1 to 15 and on one trace with no user ID, and member refs on the third trace of IDs 16
     * to 40. No trace, session or project name holds an ID text.
     *
     * @return the ID texts, ID 1 first
     */
    private List<String> seed(String pid, int d) {
        List<String> texts = new ArrayList<>();
        List<List<String>> traceIds = new ArrayList<>();
        for (int i = 1; i <= IDS; i++) {
            String text = String.format(Locale.ROOT, "pvorg-%c/probe7c1e%02d", "abcde".charAt(i % 5), i);
            texts.add(text);
            int last = i <= 38 ? d - i % 3 : d - 22 - i % 7;
            List<String> ids = new ArrayList<>();
            for (int day = d - 70 + i % 10; day <= last; day += 2 + i % 2) {
                String id = "t-" + i + "-" + day;
                trace(pid, id, text, "sn-" + i + "-" + day, day, 7 + (day + i) % 13, 13 + i % 40);
                ids.add(id);
            }
            traceIds.add(ids);
        }
        for (int day = d - 70; day <= d; day += 4) trace(pid, "t-anon-" + day, null, null, day, 11, 29);

        String caseId = "case-" + pid;
        String findingId = "finding-" + pid;
        String at = Instant.now().toString();
        jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, metric,
                                       state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at)
                VALUES (:id, :pid, 1, 'tool_error', 'classifier', 'cls-tool', 'search tool', 'rate', 'open',
                        'search tool errors', 'basis', 0.5, :at, :at, :at, :at)
                """).param("id", caseId).param("pid", pid).param("at", at).update();
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, status,
                                     onset_at, last_seen_at, payload, case_id, created_at, updated_at)
                VALUES (:id, :pid, 'tool_error', :id, 'classifier', 'cls-tool', 'open', :at, :at, '{}'::jsonb, :case,
                        :at, :at)
                """)
                .param("id", findingId)
                .param("pid", pid)
                .param("case", caseId)
                .param("at", at)
                .update();
        for (int i = 1; i <= 15; i++)
            evidence(pid, findingId, traceIds.get(i - 1).get(1), "witness");
        evidence(pid, findingId, "t-anon-" + (d - 70), "witness");
        for (int i = 16; i <= 40; i++)
            evidence(pid, findingId, traceIds.get(i - 1).get(2), "member");
        return texts;
    }

    /** Every event that reaches the root logger until {@link #close()}, with the end-user package at TRACE. */
    private static final class RootCapture {
        private final Logger root;
        private final Logger endUsers;
        private final @Nullable Level before;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private RootCapture() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            endUsers = context.getLogger(END_USERS_PACKAGE);
            before = endUsers.getLevel();
            appender.setContext(context);
        }

        static RootCapture start() {
            RootCapture capture = new RootCapture();
            capture.appender.start();
            capture.endUsers.setLevel(Level.TRACE);
            capture.root.addAppender(capture.appender);
            return capture;
        }

        void close() {
            root.detachAppender(appender);
            endUsers.setLevel(before);
            appender.stop();
        }

        List<ILoggingEvent> events() {
            return appender.list;
        }
    }

    private static @Nullable String field(ILoggingEvent e, String name) {
        if (e.getKeyValuePairs() == null) return null;
        return e.getKeyValuePairs().stream()
                .filter(kv -> kv.key.equals(name))
                .map(kv -> String.valueOf(kv.value))
                .findFirst()
                .orElse(null);
    }

    /** Everything an appender could write: message, arguments, fields, MDC and the exception chain. */
    private static String rendered(ILoggingEvent e) {
        StringBuilder out = new StringBuilder();
        out.append(e.getLoggerName())
                .append('\n')
                .append(e.getFormattedMessage())
                .append('\n');
        if (e.getArgumentArray() != null) {
            for (Object arg : e.getArgumentArray()) out.append(value(arg)).append('\n');
        }
        if (e.getKeyValuePairs() != null) {
            e.getKeyValuePairs()
                    .forEach(kv -> out.append(kv.key)
                            .append('=')
                            .append(value(kv.value))
                            .append('\n'));
        }
        e.getMDCPropertyMap()
                .forEach((k, v) -> out.append(k).append('=').append(v).append('\n'));
        for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
            out.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
        }
        return out.toString();
    }

    /** Raw bytes as hex and Base64, so a key passed as a {@code byte[]} is caught too. */
    private static String value(@Nullable Object v) {
        if (v instanceof byte[] bytes)
            return HEX.formatHex(bytes) + " " + Base64.getEncoder().encodeToString(bytes);
        return String.valueOf(v);
    }

    /** The daily job's stages in the worker's order. */
    private void daily(String pid, int day) {
        for (EndUsersStage stage : List.of(fold, reconcile, learn, judge, sweep)) stage.run(pid, day);
    }

    /** ID text to the hex of its stored key, for every {@code end_user} row that still has its text. */
    private Map<String, String> storedKeys(String pid) {
        Map<String, String> out = new TreeMap<>();
        jdbc.sql("SELECT id_text, encode(user_key, 'hex') AS user_key FROM end_user"
                        + " WHERE project_id = :pid AND id_text IS NOT NULL")
                .param("pid", pid)
                .query(rs -> {
                    out.put(rs.getString("id_text"), rs.getString("user_key"));
                });
        return out;
    }

    private byte[] salt(String pid) {
        return jdbc.sql("SELECT salt FROM end_user_project WHERE project_id = :pid")
                .param("pid", pid)
                .query(byte[].class)
                .single();
    }

    /** Every table named {@code end_user} or {@code end_user_*}, so a table added later is scanned too. */
    private List<String> endUserTables() {
        return jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                        + " AND (tablename = 'end_user' OR tablename LIKE 'end\\_user\\_%') ORDER BY tablename")
                .query(String.class)
                .list();
    }

    /** The last closed UTC day, which the daily job judges. */
    private static int jobDay() {
        return EndUserKeys.dayIndex(Instant.now()) - 1;
    }

    /** A settled trace on one route, at minute 7 and a second off the whole minute, so the route is no schedule. */
    private void trace(
            String pid, String id, @Nullable String userId, @Nullable String sessionId, int day, int hour, int second) {
        Timestamp at = Timestamp.from(Instant.ofEpochSecond(day * 86_400L)
                .plus(Duration.ofHours(hour))
                .plus(Duration.ofMinutes(7))
                .plusSeconds(second));
        if (sessionId != null) {
            jdbc.sql("""
                    INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                    VALUES (:pid, :id, :at, :at, :at) ON CONFLICT DO NOTHING
                    """)
                    .param("pid", pid)
                    .param("id", sessionId)
                    .param("at", at)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO trace (project_id, id, user_id, session_id, name, started_at, event_ts, is_settled,
                                   created_at)
                VALUES (:pid, :id, :user, :session, :name, :at, :at, true, :at)
                """)
                .param("pid", pid)
                .param("id", id)
                .param("user", userId)
                .param("session", sessionId)
                .param("name", TRACE_NAME)
                .param("at", at)
                .update();
    }

    private void ttl(String pid, int days) {
        jdbc.sql("""
                INSERT INTO retention_policy (id, project_id, data_class, ttl_days, created_at)
                VALUES (:id, :pid, 'traces', :days, '2026-01-01T00:00:00Z')
                """)
                .param("id", "rp-" + pid)
                .param("pid", pid)
                .param("days", days)
                .update();
    }

    private void evidence(String pid, String findingId, String traceId, String role) {
        jdbc.sql("""
                INSERT INTO finding_evidence (id, project_id, finding_id, trace_id, role, created_at)
                VALUES (:id, :pid, :fid, :tid, :role, :at)
                """)
                .param("id", Ids.ulid())
                .param("pid", pid)
                .param("fid", findingId)
                .param("tid", traceId)
                .param("role", role)
                .param("at", Instant.now().toString())
                .update();
    }

    private static TenantContext owner(Setup s) {
        return new TenantContext(s.user().id(), s.user().email(), null, null, null, null);
    }

    private static String org(Setup s) {
        return s.org().slug();
    }

    private static String proj(Setup s) {
        return s.project().slug();
    }

    private static <T> T data(ApiResponse<T> response) {
        return Objects.requireNonNull(response.data());
    }
}
