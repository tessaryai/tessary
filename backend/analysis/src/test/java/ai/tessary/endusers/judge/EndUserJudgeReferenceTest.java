// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.EndUserJudge.Judgment;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.AliasEdge;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Facts.Version;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.IssueResult.LineageResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.model.AliasVia;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The pure judge on the three frozen scenarios of the research archive, compared with the output of the reference
 * program. These expected values come from the reference specification (logic.py, spec Appendix C), by the owner's
 * decision of 2026-11-24; all other tests use hand-computed values.
 *
 * <p>Each scenario folder under {@code endusers/reference} is copied unedited: the fact rows the reference fold made,
 * and {@code expected.json}, the reference output on the same traces. The run follows the scenario's
 * {@code meta.json}: every guard off, every hit set complete with the member role as the hit role, no page-state
 * memory, and the route classes of {@code routes.json} as stored. The reference is plain v3; the guards and the memory
 * are tested by hand elsewhere. The tolerance covers the readings that differ from the reference (final-plan 3.3).
 */
class EndUserJudgeReferenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Guards ALL_OFF = new Guards(false, false, false, false, false, false, false);
    private static final String HIT_ROLE = "member";
    private static final double SHARE = 0.02;
    private static final double ONE_ID = 1.0;

    @Test
    void rhythm_matchesReference() {
        Scenario s = Scenario.load("rhythm");

        Judgment j = s.judge();

        JsonNode stages = s.expected().get("stages");
        StageCounts counts = j.states().stages();
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(
                PageState.fromWire(s.expected().get("page_state").asText()),
                j.page().state(),
                "page state"));
        checks.add(() -> assertNotNull(counts, "an ok page has stage counts"));
        if (counts != null) {
            checks.add(() -> withinShare(stages, "active", counts.active()));
            checks.add(() -> withinShare(stages, "new", counts.newIds()));
            checks.add(() -> withinShare(stages, "silent", counts.silent()));
            checks.add(() -> withinShare(stages, "stopped_named", counts.stoppedNamed()));
            checks.add(() -> withinShare(stages, "stopped_expected", counts.stoppedExpected()));
        }
        for (JsonNode issue : s.expected().get("issues")) {
            String key = issue.get("issue_id").asText();
            LineageResult r = j.issues().byLineage().get(key);
            checks.add(() -> assertNotNull(r, key + " has a result"));
            if (r == null) continue;
            checks.add(
                    () -> assertEquals(Verdict.fromWire(issue.get("verdict").asText()), r.verdict(), key + " verdict"));
            checks.add(() -> assertEquals(issue.get("shown").asBoolean(), r.shown(), key + " shown"));
            checks.add(() -> withinShare(issue, "users_hit", r.usersHit(), key));
            checks.add(() -> withinShareOrOneId(issue, "users_lost", r.lost(), key));
            checks.add(() -> withinShareOrOneId(issue, "ci_low", r.low(), key));
            checks.add(() -> withinShareOrOneId(issue, "ci_high", r.high(), key));
        }
        assertAll(checks);
    }

    @Test
    void keyIds_isRefused() {
        Scenario s = Scenario.load("key_ids");

        Judgment j = s.judge();

        assertAll(
                () -> assertEquals(
                        "aggregate", s.expected().get("page_reason_code").asText()),
                () -> assertEquals(PageState.IDS_UNRELIABLE, j.page().state()),
                () -> assertEquals(
                        PageStateReason.HEAVY_IDS,
                        j.page().reason(),
                        "IDs that carry more than one user's traffic hold 40% or more of the ID-days"));
    }

    @Test
    void replacedIds_isRefused() {
        Scenario s = Scenario.load("replaced_ids");

        Judgment j = s.judge();

        assertAll(
                () -> assertEquals(
                        "ids_replaced", s.expected().get("page_reason_code").asText()),
                () -> assertEquals(PageState.IDS_UNRELIABLE, j.page().state()),
                () -> assertEquals(
                        PageStateReason.IDS_REPLACED,
                        j.page().reason(),
                        "most IDs that ended with a repeating job went on under a new ID"));
    }

    private static void withinShare(JsonNode expected, String field, double actual) {
        withinShare(expected, field, actual, "stage");
    }

    private static void withinShare(JsonNode expected, String field, double actual, String what) {
        double want = expected.get(field).asDouble();
        assertTrue(
                Math.abs(actual - want) <= SHARE * Math.abs(want),
                what + " " + field + ": reference " + want + ", judge " + actual);
    }

    private static void withinShareOrOneId(JsonNode expected, String field, @Nullable Double actual, String what) {
        JsonNode want = expected.get(field);
        if (want.isNull()) {
            assertNull(actual, what + " " + field + ": the reference gives none");
            return;
        }
        assertNotNull(actual, what + " " + field + ": reference " + want.asDouble() + ", judge none");
        double w = want.asDouble();
        assertTrue(
                Math.abs(actual - w) <= Math.max(SHARE * Math.abs(w), ONE_ID),
                what + " " + field + ": reference " + w + ", judge " + actual);
    }

    /** One frozen scenario: the facts as the loader gives them, and the reference output. */
    private record Scenario(Facts facts, JsonNode expected) {

        Judgment judge() {
            return EndUserJudge.judge(facts, ALL_OFF, (name, rows, durationMs) -> {});
        }

        static Scenario load(String name) {
            String dir = "/endusers/reference/" + name + "/";
            JsonNode meta = json(dir + "meta.json");
            JsonNode run = meta.get("judge_run");
            List<IssueInput> issues = new ArrayList<>();
            Map<String, Integer> issueOfFinding = new HashMap<>();
            for (JsonNode issue : json(dir + "issues.json")) {
                List<String> findings = new ArrayList<>();
                for (JsonNode f : issue.get("findings")) {
                    String id = f.get("finding_id").asText();
                    findings.add(id);
                    issueOfFinding.put(id, issues.size());
                }
                JsonNode c = issue.get("case");
                issues.add(new IssueInput(
                        issue.get("issue_id").asText(),
                        c.isNull() ? null : c.get("case_id").asText(),
                        findings,
                        issue.get("complete").asBoolean(),
                        false));
            }
            Facts facts = new Facts(
                    run.get("as_of_day").asInt(),
                    run.get("t0").asInt(),
                    run.get("horizon_days").asInt(),
                    ids(csv(dir + "ids.csv")),
                    idDays(csv(dir + "id_days.csv.gz")),
                    nullDays(csv(dir + "null_days.csv.gz")),
                    aliasEdges(csv(dir + "alias_edges.csv")),
                    versions(csv(dir + "versions.csv")),
                    issues,
                    hits(csv(dir + "hits.csv"), issueOfFinding),
                    routeClasses(json(dir + "routes.json")),
                    List.of(),
                    List.of(),
                    List.of());
            return new Scenario(facts, json(dir + "expected.json"));
        }

        private static List<IdInfo> ids(List<Map<String, String>> rows) {
            Map<String, Integer> clusters = new LinkedHashMap<>();
            List<IdInfo> out = new ArrayList<>(rows.size());
            for (Map<String, String> r : rows) {
                if (Integer.parseInt(r.get("key_index")) != out.size()) {
                    throw new IllegalStateException("ids.csv is not in key index order");
                }
                String cluster = r.get("cluster_hash");
                out.add(new IdInfo(
                        r.get("shape_prefix"),
                        Integer.parseInt(r.get("shape_length")),
                        cluster.isEmpty() ? null : clusters.computeIfAbsent(cluster, k -> clusters.size()),
                        Integer.parseInt(r.get("first_day")),
                        Integer.parseInt(r.get("last_day")),
                        Integer.parseInt(r.get("sessions")),
                        "1".equals(r.get("all_sessions_link_id"))));
            }
            return out;
        }

        private static IdDays idDays(List<Map<String, String>> rows) {
            int n = rows.size();
            IdDays d = new IdDays(
                    new int[n],
                    new int[n],
                    new int[n],
                    new int[n],
                    new int[n],
                    new int[n],
                    new int[n],
                    new int[n],
                    new long[n],
                    new int[n],
                    new int[n][],
                    new long[n][]);
            for (int i = 0; i < n; i++) {
                Map<String, String> r = rows.get(i);
                String slots = r.get("slot_mask");
                d.id()[i] = Integer.parseInt(r.get("key_index"));
                d.day()[i] = Integer.parseInt(r.get("day"));
                d.routeNo()[i] = Integer.parseInt(r.get("route_no"));
                d.versionNo()[i] = Integer.parseInt(r.get("version_no"));
                d.traces()[i] = Integer.parseInt(r.get("traces"));
                d.tracesWithInput()[i] = Integer.parseInt(r.get("traces_with_input"));
                d.tracesWithParent()[i] = Integer.parseInt(r.get("traces_with_parent"));
                d.hourMask()[i] = Integer.parseInt(r.get("hour_mask"));
                d.slotMaskLow()[i] = Long.parseUnsignedLong(slots.substring(8), 16);
                d.slotMaskHigh()[i] = Integer.parseUnsignedInt(slots.substring(0, 8), 16);
                d.sessionHashes()[i] = splitInts(r.get("session_hashes"));
                d.jobKeys()[i] = splitLongs(r.get("job_keys"));
            }
            return d;
        }

        private static List<NullDay> nullDays(List<Map<String, String>> rows) {
            return rows.stream()
                    .map(r -> new NullDay(
                            Integer.parseInt(r.get("day")),
                            Integer.parseInt(r.get("route_no")),
                            Integer.parseInt(r.get("version_no")),
                            Integer.parseInt(r.get("traces")),
                            Integer.parseInt(r.get("traces_no_user_id"))))
                    .toList();
        }

        private static List<AliasEdge> aliasEdges(List<Map<String, String>> rows) {
            return rows.stream()
                    .map(r -> new AliasEdge(
                            Integer.parseInt(r.get("from_key_index")),
                            Integer.parseInt(r.get("to_key_index")),
                            AliasVia.fromWire(r.get("via")),
                            Integer.parseInt(r.get("day"))))
                    .toList();
        }

        private static List<Version> versions(List<Map<String, String>> rows) {
            return rows.stream()
                    .map(r -> new Version(
                            Integer.parseInt(r.get("version_no")),
                            r.get("project_version_id"),
                            Integer.parseInt(r.get("first_day"))))
                    .toList();
        }

        /** The rows of the hit role; an empty key index is a ref with no user ID, an empty route a session only. */
        private static List<HitRow> hits(List<Map<String, String>> rows, Map<String, Integer> issueOfFinding) {
            List<HitRow> out = new ArrayList<>();
            for (Map<String, String> r : rows) {
                if (!HIT_ROLE.equals(r.get("role"))) continue;
                String key = r.get("key_index");
                boolean sessionOnly = "1".equals(r.get("session_only"));
                Map<Integer, Integer> sessionRoutes = new TreeMap<>();
                if (sessionOnly) {
                    for (String pair : r.get("session_routes").split(";")) {
                        String[] parts = pair.split(":");
                        sessionRoutes.put(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    }
                }
                out.add(new HitRow(
                        Objects.requireNonNull(issueOfFinding.get(r.get("finding_id"))),
                        key.isEmpty() ? null : Integer.parseInt(key),
                        Integer.parseInt(r.get("day")),
                        sessionOnly ? null : Integer.parseInt(r.get("route_no")),
                        sessionRoutes,
                        Integer.parseInt(r.get("refs"))));
            }
            return out;
        }

        private static RouteClasses routeClasses(JsonNode routes) {
            Map<Integer, Route> byRoute = new TreeMap<>();
            routes.properties().forEach(e -> {
                JsonNode st = e.getValue().get("statistics");
                RouteStatistics statistics = new RouteStatistics(
                        st.get("n").asLong(),
                        0,
                        st.get("inp").asDouble(),
                        0,
                        0,
                        0,
                        st.get("follow").asDouble(),
                        nullable(st, "dq10"),
                        nullable(st, "dq90"),
                        nullable(st, "conc"),
                        st.get("sched_r").asDouble(),
                        nullable(st, "co_anchor"));
                byRoute.put(
                        Integer.parseInt(e.getKey()),
                        new Route(
                                RouteClass.fromWire(e.getValue().get("class").asText()),
                                null,
                                statistics,
                                st.get("near_cut").asBoolean()));
            });
            return new RouteClasses(byRoute, byRoute.values().stream().noneMatch(r -> r.routeClass() == RouteClass.A));
        }

        private static @Nullable Double nullable(JsonNode node, String field) {
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asDouble();
        }

        private static int[] splitInts(String text) {
            if (text.isEmpty()) return new int[0];
            String[] parts = text.split(";");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i]);
            return out;
        }

        private static long[] splitLongs(String text) {
            if (text.isEmpty()) return new long[0];
            String[] parts = text.split(";");
            long[] out = new long[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i]);
            return out;
        }

        private static JsonNode json(String path) {
            try (InputStream in = open(path)) {
                return JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static List<Map<String, String>> csv(String path) {
            try (InputStream raw = open(path);
                    InputStream in = path.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String[] header = reader.readLine().split(",", -1);
                List<Map<String, String>> out = new ArrayList<>();
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String[] cells = line.split(",", -1);
                    Map<String, String> row = new HashMap<>();
                    for (int i = 0; i < header.length; i++) row.put(header[i], cells[i]);
                    out.add(row);
                }
                return out;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static InputStream open(String path) {
            return Objects.requireNonNull(EndUserJudgeReferenceTest.class.getResourceAsStream(path), path);
        }
    }
}
