// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.facts.DayFacts;
import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.RouteClassRepository;
import ai.tessary.endusers.facts.ShapeSignature;
import ai.tessary.endusers.hits.EndUserHitRepository;
import ai.tessary.endusers.hits.EndUserHitRepository.Finding;
import ai.tessary.endusers.hits.EndUserHitRepository.SessionRoutes;
import ai.tessary.endusers.hits.HitRule;
import ai.tessary.endusers.hits.HitRules;
import ai.tessary.endusers.hits.IssueLineages;
import ai.tessary.endusers.hits.IssueLineages.IssueLineage;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.AliasEdge;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Facts.Version;
import ai.tessary.endusers.model.AliasVia;
import ai.tessary.endusers.model.PageState;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Loads the judge's facts for the window of days {@code [fromDay, asOfDay]}. A user ID is its key index, its place in
 * {@code user_key} order among the IDs with a day row in the window; the keys come back beside the facts for the write
 * and never enter them. Day rows stream into primitive columns. The caller holds one read-only repeatable-read
 * transaction, so every read sees one state of the facts and the driver fetches rows in pages.
 */
@Repository
public class FactsLoadRepository {

    private static final int FETCH_ROWS = 10_000;
    private static final int INITIAL_ROWS = 16;
    private static final HexFormat HEX = HexFormat.of();
    private static final String NO_USER_KEY = HEX.formatHex(new byte[EndUserKeys.KEY_BYTES]);
    private static final int[] NO_INTS = {};
    private static final long[] NO_LONGS = {};

    private final JdbcClient jdbc;
    private final RouteClassRepository routes;
    private final EndUserHitRepository hits;
    private final IssueLineages lineages;
    private final HitRules rules;
    private final EndUserHistoryHorizon horizon;

    public FactsLoadRepository(
            DataSource dataSource,
            RouteClassRepository routes,
            EndUserHitRepository hits,
            IssueLineages lineages,
            HitRules rules,
            EndUserHistoryHorizon horizon) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_ROWS);
        this.jdbc = JdbcClient.create(template);
        this.routes = routes;
        this.hits = hits;
        this.lineages = lineages;
        this.rules = rules;
        this.horizon = horizon;
    }

    /** @param keys key index to {@code user_key} */
    public record Loaded(Facts facts, List<byte[]> keys) {}

    /** Distinct (user key, day) pairs in the window: the size the judge would hold. */
    public long idDays(String projectId, int fromDay, int toDay) {
        return jdbc.sql("""
                SELECT count(*) FROM (SELECT DISTINCT user_key, day FROM end_user_day
                                       WHERE project_id = :pid AND day BETWEEN :from AND :to) AS d
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(Long.class)
                .single();
    }

    public List<NullDay> nullDays(String projectId, int fromDay, int toDay) {
        return jdbc.sql("""
                SELECT day, route_no, version_no, traces, traces_no_user_id FROM end_user_day_null
                 WHERE project_id = :pid AND day BETWEEN :from AND :to
                 ORDER BY day, route_no, version_no
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query((rs, n) -> new NullDay(
                        rs.getInt("day"),
                        rs.getInt("route_no"),
                        rs.getInt("version_no"),
                        rs.getInt("traces"),
                        rs.getInt("traces_no_user_id")))
                .list();
    }

    /**
     * @param t0 the stored history start day
     * @param memory the rule states of the last two runs, oldest first
     */
    public Loaded load(String projectId, int asOfDay, int fromDay, int t0, List<PageState> memory) {
        DayColumns days = dayRows(projectId, fromDay, asOfDay);
        List<IdInfo> ids = idInfos(projectId, fromDay, asOfDay, days);
        List<IssueLineage> issueLineages = lineages.lineages(projectId);
        List<IssueInput> issues = new ArrayList<>(issueLineages.size());
        List<HitRow> hitRows = new ArrayList<>();
        for (int i = 0; i < issueLineages.size(); i++) {
            IssueLineage lineage = issueLineages.get(i);
            boolean stored = hitRows(projectId, i, lineage.findingIds(), fromDay, asOfDay, days.index, hitRows);
            issues.add(new IssueInput(
                    lineage.lineageKey(),
                    lineage.currentCaseId(),
                    lineage.findingIds(),
                    lineage.complete(),
                    lineage.metricShift() && !stored));
        }
        OptionalInt kept = horizon.days(projectId);
        Facts facts = new Facts(
                asOfDay,
                t0,
                kept.isPresent() ? kept.getAsInt() : null,
                ids,
                days.columns(),
                nullDays(projectId, fromDay, asOfDay),
                aliasEdges(projectId, fromDay, asOfDay, days.index),
                versions(projectId),
                issues,
                hitRows,
                routes.stored(projectId),
                foldDays(projectId, fromDay, asOfDay, "outage"),
                foldDays(projectId, fromDay, asOfDay, "gap"),
                memory);
        return new Loaded(facts, days.keys);
    }

    private DayColumns dayRows(String projectId, int fromDay, int toDay) {
        DayColumns out = new DayColumns();
        jdbc.sql("""
                SELECT encode(user_key, 'hex') AS user_key, day, route_no, version_no, traces, traces_with_input,
                       traces_with_parent, hour_mask, slot_mask::text AS slot_mask, session_hashes, job_keys
                  FROM end_user_day
                 WHERE project_id = :pid AND day BETWEEN :from AND :to
                 ORDER BY user_key, day, route_no, version_no
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(out::add);
        out.endId();
        return out;
    }

    /** The stored ID of each key index; its last day is its last day row in the window. */
    private List<IdInfo> idInfos(String projectId, int fromDay, int toDay, DayColumns days) {
        record Stored(String shape, @Nullable String cluster, int firstDay, boolean tokenLink) {}
        Map<String, Stored> byKey = new HashMap<>();
        jdbc.sql("""
                SELECT encode(u.user_key, 'hex') AS user_key, u.shape_signature, encode(u.cluster_hash, 'hex') AS cluster,
                       u.first_seen_day, u.session_token_link
                  FROM end_user u
                 WHERE u.project_id = :pid
                   AND EXISTS (SELECT 1 FROM end_user_day d
                                WHERE d.project_id = u.project_id AND d.user_key = u.user_key
                                  AND d.day BETWEEN :from AND :to)
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(rs -> {
                    byKey.put(
                            rs.getString("user_key"),
                            new Stored(
                                    rs.getString("shape_signature"),
                                    rs.getString("cluster"),
                                    rs.getInt("first_seen_day"),
                                    rs.getBoolean("session_token_link")));
                });
        Map<String, Integer> clusters = new HashMap<>();
        List<IdInfo> ids = new ArrayList<>(days.keys.size());
        for (int u = 0; u < days.keys.size(); u++) {
            String key = HEX.formatHex(days.keys.get(u));
            Stored s = byKey.get(key);
            if (s == null) throw new IllegalStateException("a key index has day rows and no end_user row");
            ShapeSignature shape = ShapeSignature.parse(s.shape());
            String cluster = s.cluster();
            ids.add(new IdInfo(
                    shape.prefix(),
                    shape.length(),
                    cluster == null ? null : clusters.computeIfAbsent(cluster, c -> clusters.size()),
                    s.firstDay(),
                    days.lastDay.get(u),
                    days.sessions.get(u),
                    s.tokenLink()));
        }
        return ids;
    }

    /**
     * Appends the hit rows of one issue: the stored rows of its findings in the window whose role is a hit role under
     * the finding's rule, read from every role the finding has stored. A key with no index (no user ID, or no day row in
     * the window) is a ref with no user ID: its evidence still counts for the issue's days and reach.
     *
     * @return any finding of the issue has a stored row, in any role and on any day
     */
    private boolean hitRows(
            String projectId,
            int issue,
            List<String> findingIds,
            int fromDay,
            int toDay,
            Map<String, Integer> index,
            List<HitRow> out) {
        boolean stored = false;
        for (Finding finding : hits.findings(projectId, findingIds)) {
            List<EndUserHitRepository.HitRow> rows = hits.stored(projectId, finding.id());
            stored |= !rows.isEmpty();
            HitRule rule = rules.ruleFor(finding.hitFinding());
            Set<String> hitRoles = rule.hitRoles(
                    rows.stream().map(EndUserHitRepository.HitRow::role).collect(Collectors.toSet()));
            for (EndUserHitRepository.HitRow row : rows) {
                if (!hitRoles.contains(row.role()) || row.day() < fromDay || row.day() > toDay) continue;
                Integer id = NO_USER_KEY.equals(row.userKey()) ? null : index.get(row.userKey());
                if (row.routeNo() != EndUserHitRepository.SESSION_ONLY_ROUTE) {
                    out.add(new HitRow(issue, id, row.day(), row.routeNo(), Map.of(), row.refs()));
                    continue;
                }
                for (SessionRoutes session : row.sessionRoutes()) {
                    out.add(new HitRow(issue, id, row.day(), null, session.routes(), session.refs()));
                }
            }
        }
        return stored;
    }

    /** Edges whose two IDs both have a day row in the window. */
    private List<AliasEdge> aliasEdges(String projectId, int fromDay, int toDay, Map<String, Integer> index) {
        List<AliasEdge> out = new ArrayList<>();
        jdbc.sql("""
                SELECT encode(from_key, 'hex') AS from_key, encode(to_key, 'hex') AS to_key, via, day
                  FROM end_user_alias_edge
                 WHERE project_id = :pid AND day BETWEEN :from AND :to
                 ORDER BY day, from_key, to_key, via
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(rs -> {
                    Integer from = index.get(rs.getString("from_key"));
                    Integer to = index.get(rs.getString("to_key"));
                    if (from != null && to != null) {
                        out.add(new AliasEdge(from, to, AliasVia.fromWire(rs.getString("via")), rs.getInt("day")));
                    }
                });
        return out;
    }

    private List<Version> versions(String projectId) {
        return jdbc.sql("""
                SELECT version_no, project_version_id, first_trace_day FROM end_user_version
                 WHERE project_id = :pid ORDER BY version_no
                """)
                .param("pid", projectId)
                .query((rs, n) -> new Version(
                        rs.getInt("version_no"), rs.getString("project_version_id"), rs.getInt("first_trace_day")))
                .list();
    }

    /** @param flag {@code outage} or {@code gap} */
    private List<Integer> foldDays(String projectId, int fromDay, int toDay, String flag) {
        return jdbc.sql("SELECT day FROM end_user_fold_day WHERE project_id = :pid AND " + flag
                        + " AND day BETWEEN :from AND :to ORDER BY day")
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(Integer.class)
                .list();
    }

    /** The day rows as growing primitive columns, one ID at a time in key order. */
    private static final class DayColumns {
        final List<byte[]> keys = new ArrayList<>();
        final Map<String, Integer> index = new LinkedHashMap<>();
        final List<Integer> lastDay = new ArrayList<>();
        final List<Integer> sessions = new ArrayList<>();
        private final Set<Integer> idSessions = new HashSet<>();
        private int size;
        private int[] id = new int[INITIAL_ROWS];
        private int[] day = new int[INITIAL_ROWS];
        private int[] routeNo = new int[INITIAL_ROWS];
        private int[] versionNo = new int[INITIAL_ROWS];
        private int[] traces = new int[INITIAL_ROWS];
        private int[] tracesWithInput = new int[INITIAL_ROWS];
        private int[] tracesWithParent = new int[INITIAL_ROWS];
        private int[] hourMask = new int[INITIAL_ROWS];
        private long[] slotMaskLow = new long[INITIAL_ROWS];
        private int[] slotMaskHigh = new int[INITIAL_ROWS];
        private int[][] sessionHashes = new int[INITIAL_ROWS][];
        private long[][] jobKeys = new long[INITIAL_ROWS][];

        void add(ResultSet rs) throws SQLException {
            String key = rs.getString("user_key");
            Integer u = index.get(key);
            if (u == null) {
                endId();
                u = keys.size();
                index.put(key, u);
                keys.add(HEX.parseHex(key));
            }
            if (size == id.length) grow();
            int d = rs.getInt("day");
            String slots = rs.getString("slot_mask");
            id[size] = u;
            day[size] = d;
            routeNo[size] = rs.getInt("route_no");
            versionNo[size] = rs.getInt("version_no");
            traces[size] = rs.getInt("traces");
            tracesWithInput[size] = rs.getInt("traces_with_input");
            tracesWithParent[size] = rs.getInt("traces_with_parent");
            hourMask[size] = rs.getInt("hour_mask");
            slotMaskLow[size] = DayFacts.slotMaskLow(slots);
            slotMaskHigh[size] = DayFacts.slotMaskHigh(slots);
            sessionHashes[size] = ints(rs.getArray("session_hashes"));
            jobKeys[size] = longs(rs.getArray("job_keys"));
            for (int h : sessionHashes[size]) idSessions.add(h);
            if (lastDay.size() == u) lastDay.add(d);
            else lastDay.set(u, d);
            size++;
        }

        /** Closes the ID being read: the count of its distinct session hashes. */
        void endId() {
            if (sessions.size() < keys.size()) sessions.add(idSessions.size());
            idSessions.clear();
        }

        IdDays columns() {
            return new IdDays(
                    Arrays.copyOf(id, size),
                    Arrays.copyOf(day, size),
                    Arrays.copyOf(routeNo, size),
                    Arrays.copyOf(versionNo, size),
                    Arrays.copyOf(traces, size),
                    Arrays.copyOf(tracesWithInput, size),
                    Arrays.copyOf(tracesWithParent, size),
                    Arrays.copyOf(hourMask, size),
                    Arrays.copyOf(slotMaskLow, size),
                    Arrays.copyOf(slotMaskHigh, size),
                    Arrays.copyOf(sessionHashes, size),
                    Arrays.copyOf(jobKeys, size));
        }

        private void grow() {
            int n = id.length * 2;
            id = Arrays.copyOf(id, n);
            day = Arrays.copyOf(day, n);
            routeNo = Arrays.copyOf(routeNo, n);
            versionNo = Arrays.copyOf(versionNo, n);
            traces = Arrays.copyOf(traces, n);
            tracesWithInput = Arrays.copyOf(tracesWithInput, n);
            tracesWithParent = Arrays.copyOf(tracesWithParent, n);
            hourMask = Arrays.copyOf(hourMask, n);
            slotMaskLow = Arrays.copyOf(slotMaskLow, n);
            slotMaskHigh = Arrays.copyOf(slotMaskHigh, n);
            sessionHashes = Arrays.copyOf(sessionHashes, n);
            jobKeys = Arrays.copyOf(jobKeys, n);
        }

        private static int[] ints(Array array) throws SQLException {
            Integer[] values = (Integer[]) array.getArray();
            int[] out = values.length == 0 ? NO_INTS : new int[values.length];
            for (int i = 0; i < values.length; i++) out[i] = values[i];
            return out;
        }

        private static long[] longs(Array array) throws SQLException {
            Long[] values = (Long[]) array.getArray();
            long[] out = values.length == 0 ? NO_LONGS : new long[values.length];
            for (int i = 0; i < values.length; i++) out[i] = values[i];
            return out;
        }
    }
}
