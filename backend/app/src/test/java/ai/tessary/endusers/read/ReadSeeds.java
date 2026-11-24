// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.facts.EndUserKeys;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Raw rows for the read tests, written as the fold, the hit sweep and the judge write them: keys from the project's
 * salt, codes in their wire form, jsonb in the snake_case shape of the judge records.
 */
record ReadSeeds(JdbcClient jdbc, String pid) {

    static final byte[] SALT = salt();

    static final byte[] NO_USER_KEY = new byte[EndUserKeys.KEY_BYTES];

    static byte[] key(String userId) {
        return EndUserKeys.userKey(SALT, userId);
    }

    static String md5(byte[] key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(key));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Case and finding ids are unique across projects, so a test's short name is stored with the project id. */
    String id(String name) {
        return name + "-" + pid;
    }

    void project(@Nullable Integer historyStartDay) {
        jdbc.sql("INSERT INTO end_user_project (project_id, salt, history_start_day) VALUES (:pid, :salt, :day)")
                .param("pid", pid)
                .param("salt", SALT)
                .param("day", historyStartDay)
                .update();
    }

    void snapshot(
            int day,
            String pageState,
            @Nullable String reason,
            String reasonNumbers,
            String banners,
            String limits,
            @Nullable String trust,
            @Nullable String stageCounts,
            @Nullable String setAside,
            @Nullable String stopStage,
            @Nullable String stopStageNumbers) {
        jdbc.sql("""
                INSERT INTO end_user_snapshot (project_id, day, logic_version, page_state, page_state_reason,
                                               reason_numbers, banners, limits, trust, stage_counts, set_aside,
                                               stop_stage_state, stop_stage_numbers)
                VALUES (:pid, :day, 'v3', :state, :reason, CAST(:numbers AS jsonb), CAST(:banners AS jsonb),
                        CAST(:limits AS jsonb), CAST(:trust AS jsonb), CAST(:stages AS jsonb),
                        CAST(:setAside AS jsonb), :stopStage, CAST(:stopNumbers AS jsonb))
                """)
                .param("pid", pid)
                .param("day", day)
                .param("state", pageState)
                .param("reason", reason)
                .param("numbers", reasonNumbers)
                .param("banners", banners)
                .param("limits", limits)
                .param("trust", trust)
                .param("stages", stageCounts)
                .param("setAside", setAside)
                .param("stopStage", stopStage)
                .param("stopNumbers", stopStageNumbers)
                .update();
    }

    /** A snapshot with only a page state, for reads that use the snapshot day and state alone. */
    void snapshot(int day, String pageState) {
        snapshot(day, pageState, null, "{}", "[]", "[]", null, null, null, null, null);
    }

    void caseRow(String name, long seq, String detector, boolean resolved) {
        jdbc.sql("""
                INSERT INTO eval_case (id, project_id, seq, detector, subject_kind, subject_id, subject_label, metric,
                                       state, title, basis, severity, onset_at, opened_at, last_seen_at, updated_at,
                                       resolved_at, resolution)
                VALUES (:id, :pid, :seq, :detector, 'classifier', :subject, :label, 'rate', :state, :title, 'basis',
                        0.5, '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z',
                        '2026-09-22T00:00:00Z', :resolvedAt, :resolution)
                """)
                .param("id", id(name))
                .param("pid", pid)
                .param("seq", seq)
                .param("detector", detector)
                .param("subject", "subject-" + name)
                .param("label", "label " + name)
                .param("state", resolved ? "resolved" : "open")
                .param("title", "title " + name)
                .param("resolvedAt", resolved ? "2026-10-01T00:00:00Z" : null)
                .param("resolution", resolved ? "human" : null)
                .update();
    }

    void finding(String name, String classifierKey, @Nullable String caseName) {
        jdbc.sql("""
                INSERT INTO finding (id, project_id, classifier_key, cause_key, subject_kind, subject_id, status,
                                     onset_at, last_seen_at, payload, case_id, created_at, updated_at)
                VALUES (:id, :pid, :key, :id, 'classifier', :subject, 'open', '2026-09-22T00:00:00Z',
                        '2026-09-22T00:00:00Z', '{}'::jsonb, :case, '2026-09-22T00:00:00Z', '2026-09-22T00:00:00Z')
                """)
                .param("id", id(name))
                .param("pid", pid)
                .param("key", classifierKey)
                .param("subject", "cls-" + name)
                .param("case", caseName == null ? null : id(caseName))
                .update();
    }

    void hit(String findingName, String role, byte[] userKey, int day, int routeNo, int refs) {
        jdbc.sql("""
                INSERT INTO end_user_hit (project_id, finding_id, role, user_key, day, route_no, refs, source)
                VALUES (:pid, :fid, :role, :key, :day, :route, :refs, 'evidence')
                """)
                .param("pid", pid)
                .param("fid", id(findingName))
                .param("role", role)
                .param("key", userKey)
                .param("day", day)
                .param("route", routeNo)
                .param("refs", refs)
                .update();
    }

    void summary(String findingName, int refsTotal, int refsNoUserId) {
        jdbc.sql("""
                INSERT INTO end_user_hit_summary (project_id, finding_id, refs_total, refs_no_user_id,
                                                  session_only_refs, complete, hit_rule)
                VALUES (:pid, :fid, :total, :noId, 0, true, 'tool_error')
                """)
                .param("pid", pid)
                .param("fid", id(findingName))
                .param("total", refsTotal)
                .param("noId", refsNoUserId)
                .update();
    }

    /** A user ID as the judge leaves it. */
    void endUser(
            byte[] userKey,
            @Nullable String idText,
            @Nullable String stage,
            @Nullable String state,
            @Nullable String substate,
            @Nullable String grade,
            @Nullable Integer daysSilent,
            @Nullable Double pStop,
            @Nullable String setAside) {
        jdbc.sql("""
                INSERT INTO end_user (project_id, user_key, id_text, shape_signature, first_seen_day, last_seen_day,
                                      state, substate, grade, p_stop, days_silent, set_aside_reason, stage)
                VALUES (:pid, :key, :text, 'a9', 20300, 20350, :state, :substate, :grade, :pStop, :silent, :setAside,
                        :stage)
                """)
                .param("pid", pid)
                .param("key", userKey)
                .param("text", idText)
                .param("state", state)
                .param("substate", substate)
                .param("grade", grade)
                .param("pStop", pStop)
                .param("silent", daysSilent)
                .param("setAside", setAside)
                .param("stage", stage)
                .update();
    }

    /** A user ID the fold saw and no judge run has judged. */
    void foldedUser(byte[] userKey, String idText) {
        endUser(userKey, idText, null, null, null, null, null, null, null);
    }

    void stoppedUser(byte[] userKey, String idText, int daysSilent) {
        endUser(userKey, idText, "stopped", "stopped", null, "stopped", daysSilent, 0.9, null);
    }

    void issue(
            String lineageKey,
            int day,
            @Nullable String caseName,
            String[] findingNames,
            @Nullable Integer rank,
            int usersHit,
            String verdict,
            @Nullable Integer compared,
            @Nullable Double lost,
            @Nullable Double low,
            @Nullable Double high,
            @Nullable String groupId,
            @Nullable String curve,
            String diagnostics) {
        jdbc.sql("""
                INSERT INTO end_user_issue (project_id, lineage_key, day, case_id, finding_ids, rank, user_ids_hit,
                                            compared, lost, low, high, verdict, shown, group_id, curve, diagnostics)
                VALUES (:pid, :lineage, :day, :case, :findings, :rank, :hit, :compared, :lost, :low, :high,
                        :verdict, true, :group, CAST(:curve AS jsonb), CAST(:diagnostics AS jsonb))
                """)
                .param("pid", pid)
                .param("lineage", lineageKey)
                .param("day", day)
                .param("case", caseName == null ? null : id(caseName))
                .param("findings", Arrays.stream(findingNames).map(this::id).toArray(String[]::new))
                .param("rank", rank)
                .param("hit", usersHit)
                .param("compared", compared)
                .param("lost", lost)
                .param("low", low)
                .param("high", high)
                .param("verdict", verdict)
                .param("group", groupId)
                .param("curve", curve)
                .param("diagnostics", diagnostics)
                .update();
    }

    /** An issue with a verdict and no numbers. */
    void issue(String lineageKey, int day, @Nullable String caseName, String[] findingNames, int rank, String verdict) {
        issue(lineageKey, day, caseName, findingNames, rank, 0, verdict, null, null, null, null, null, null, "{}");
    }

    void issueHit(String lineageKey, byte[] userKey, int firstHitDay) {
        jdbc.sql("""
                INSERT INTO end_user_issue_hit (project_id, lineage_key, user_key, first_hit_day, in_estimate)
                VALUES (:pid, :lineage, :key, :day, true)
                """)
                .param("pid", pid)
                .param("lineage", lineageKey)
                .param("key", userKey)
                .param("day", firstHitDay)
                .update();
    }

    void day(byte[] userKey, int day) {
        jdbc.sql("""
                INSERT INTO end_user_day (project_id, user_key, day, route_no, version_no, traces, hour_mask,
                                          slot_mask)
                VALUES (:pid, :key, :day, 1, 1, 3, 1, lpad('1', 96, '0')::bit(96))
                """).param("pid", pid).param("key", userKey).param("day", day).update();
    }

    void aliasEdge(byte[] from, byte[] to, int day) {
        jdbc.sql("""
                INSERT INTO end_user_alias_edge (project_id, from_key, to_key, via, day)
                VALUES (:pid, :from, :to, 'session', :day)
                """)
                .param("pid", pid)
                .param("from", from)
                .param("to", to)
                .param("day", day)
                .update();
    }

    int rows(String table) {
        return jdbc.sql("SELECT COUNT(*)::int FROM " + table + " WHERE project_id = :pid")
                .param("pid", pid)
                .query(Integer.class)
                .single();
    }

    int rowsOfKey(String table, byte[] userKey) {
        return jdbc.sql("SELECT COUNT(*)::int FROM " + table + " WHERE project_id = :pid AND user_key = :key")
                .param("pid", pid)
                .param("key", userKey)
                .query(Integer.class)
                .single();
    }

    private static byte[] salt() {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 7);
        return salt;
    }
}
