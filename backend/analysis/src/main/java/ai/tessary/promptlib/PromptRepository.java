// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.PromptListQuery;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Prompt, version and label storage. Every statement is keyed on {@code project_id}, so an id from another project
 * reads as absent and writes nothing. Folder and search filters use {@code starts_with} and an escaped
 * {@code ILIKE}, never a bare {@code LIKE}, because {@code _} and {@code %} are legal in names.
 */
@Repository
public class PromptRepository {

    private static final String PROMPT_COLS =
            "p.id, p.project_id, p.name, p.type, p.tags, p.last_version, p.created_by, p.created_at, p.updated_at";
    private static final String VERSION_COLS =
            "id, project_id, prompt_id, version, body, config, commit_message, created_by, created_via, created_at";

    /** The part of a name after the listed folder's prefix; {@code substr} counts characters, so the offset does too. */
    private static final String REST_OF_NAME = "substr(p.name, :levelOffset)";

    private final JdbcClient jdbc;

    public PromptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws {@code DuplicateKeyException} on a taken name and {@code DataIntegrityViolationException} on a CHECK. */
    public void insertPrompt(PromptRow row) {
        jdbc.sql("INSERT INTO prompt (id, project_id, name, type, tags, last_version, created_by, created_at,"
                        + " updated_at) VALUES (:id, :pid, :name, :type, CAST(:tags AS JSONB), :lastVersion, :by,"
                        + " :created, :updated)")
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("name", row.name())
                .param("type", row.type().wire())
                .param("tags", PromptJson.write(row.tags()))
                .param("lastVersion", row.lastVersion())
                .param("by", row.createdBy())
                .param("created", ts(row.createdAt()))
                .param("updated", ts(row.updatedAt()))
                .update();
    }

    /** Serializes version and label writes on one prompt for the rest of the transaction. */
    public Optional<String> lockPrompt(String projectId, String id) {
        return jdbc.sql("SELECT id FROM prompt WHERE project_id = :pid AND id = :id FOR UPDATE")
                .param("pid", projectId)
                .param("id", id)
                .query(String.class)
                .optional();
    }

    /**
     * Hands out the next version number by raising {@code last_version}, which never goes down, so a deleted number is
     * never handed out again. Call it under {@link #lockPrompt}.
     */
    public int claimVersion(String projectId, String promptId) {
        return jdbc.sql("UPDATE prompt SET last_version = last_version + 1"
                        + " WHERE project_id = :pid AND id = :promptId RETURNING last_version")
                .param("pid", projectId)
                .param("promptId", promptId)
                .query(Integer.class)
                .single();
    }

    /** {@code latest} without reading every body; 0 when the prompt has no version. */
    public int maxVersion(String projectId, String promptId) {
        return jdbc.sql("SELECT COALESCE(MAX(version), 0) FROM prompt_version"
                        + " WHERE project_id = :pid AND prompt_id = :promptId")
                .param("pid", projectId)
                .param("promptId", promptId)
                .query(Integer.class)
                .single();
    }

    public int countVersions(String projectId, String promptId) {
        return jdbc.sql("SELECT count(*) FROM prompt_version WHERE project_id = :pid AND prompt_id = :promptId")
                .param("pid", projectId)
                .param("promptId", promptId)
                .query(Integer.class)
                .single();
    }

    /** Throws {@code DuplicateKeyException} when a concurrent write took the same version number. */
    public void insertVersion(PromptVersionRow row) {
        jdbc.sql("INSERT INTO prompt_version (id, project_id, prompt_id, version, body, config, commit_message,"
                        + " created_by, created_via, created_at) VALUES (:id, :pid, :promptId, :version,"
                        + " CAST(:body AS JSONB), CAST(:config AS JSONB), :commit, :by, :via, :created)")
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("promptId", row.promptId())
                .param("version", row.version())
                .param("body", row.bodyJson())
                .param("config", row.configJson())
                .param("commit", row.commitMessage())
                .param("by", row.createdBy())
                .param("via", row.createdVia().wire())
                .param("created", ts(row.createdAt()))
                .update();
    }

    public void touchPrompt(String projectId, String promptId, Instant now) {
        jdbc.sql("UPDATE prompt SET updated_at = :now WHERE project_id = :pid AND id = :promptId")
                .param("now", ts(now))
                .param("pid", projectId)
                .param("promptId", promptId)
                .update();
    }

    public void updateTags(String projectId, String promptId, List<String> tags, Instant now) {
        jdbc.sql("UPDATE prompt SET tags = CAST(:tags AS JSONB), updated_at = :now"
                        + " WHERE project_id = :pid AND id = :promptId")
                .param("tags", PromptJson.write(tags))
                .param("now", ts(now))
                .param("pid", projectId)
                .param("promptId", promptId)
                .update();
    }

    /** The version a label points at, row-locked until the transaction ends so the move that follows is not lost. */
    public Optional<Integer> labelVersion(String projectId, String promptId, String label) {
        return jdbc.sql("SELECT v.version FROM prompt_label l JOIN prompt_version v ON v.id = l.version_id"
                        + " WHERE l.project_id = :pid AND l.prompt_id = :promptId AND l.label = :label FOR UPDATE OF l")
                .param("pid", projectId)
                .param("promptId", promptId)
                .param("label", label)
                .query(Integer.class)
                .optional();
    }

    public void upsertLabel(String projectId, String promptId, String label, String versionId, String by, Instant now) {
        jdbc.sql("INSERT INTO prompt_label (project_id, prompt_id, label, version_id, updated_by, updated_at)"
                        + " VALUES (:pid, :promptId, :label, :versionId, :by, :now)"
                        + " ON CONFLICT (prompt_id, label) DO UPDATE SET version_id = EXCLUDED.version_id,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at")
                .param("pid", projectId)
                .param("promptId", promptId)
                .param("label", label)
                .param("versionId", versionId)
                .param("by", by)
                .param("now", ts(now))
                .update();
    }

    public boolean deleteLabel(String projectId, String promptId, String label) {
        return jdbc.sql("DELETE FROM prompt_label WHERE project_id = :pid AND prompt_id = :promptId AND label = :label")
                        .param("pid", projectId)
                        .param("promptId", promptId)
                        .param("label", label)
                        .update()
                > 0;
    }

    /** Ordered by prompt id, then label. */
    public List<PromptLabelRow> labelsOf(String projectId, List<String> promptIds) {
        if (promptIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT l.project_id, l.prompt_id, l.label, l.version_id, v.version, l.updated_by,"
                        + " l.updated_at FROM prompt_label l JOIN prompt_version v ON v.id = l.version_id"
                        + " WHERE l.project_id = :pid AND l.prompt_id = ANY(:ids) ORDER BY l.prompt_id, l.label")
                .param("pid", projectId)
                .param("ids", promptIds.toArray(String[]::new))
                .query((rs, n) -> new PromptLabelRow(
                        rs.getString("project_id"),
                        rs.getString("prompt_id"),
                        rs.getString("label"),
                        rs.getString("version_id"),
                        rs.getInt("version"),
                        rs.getString("updated_by"),
                        instant(rs, "updated_at")))
                .list();
    }

    public Optional<PromptRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + PROMPT_COLS + " FROM prompt p WHERE p.project_id = :pid AND p.id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> prompt(rs))
                .optional();
    }

    public Optional<PromptRow> findByName(String projectId, String name) {
        return jdbc.sql("SELECT " + PROMPT_COLS + " FROM prompt p WHERE p.project_id = :pid AND p.name = :name")
                .param("pid", projectId)
                .param("name", name)
                .query((rs, n) -> prompt(rs))
                .optional();
    }

    /** Newest first, at most {@code limit}, only numbers below {@code beforeVersion} when it is given. */
    public List<PromptVersionSummaryRow> versionsOf(
            String projectId, String promptId, int limit, @Nullable Integer beforeVersion) {
        return jdbc.sql("SELECT id, version, commit_message, created_by, created_via, created_at FROM prompt_version"
                        + " WHERE project_id = :pid AND prompt_id = :promptId"
                        + (beforeVersion == null ? "" : " AND version < :before")
                        + " ORDER BY version DESC LIMIT :limit")
                .param("pid", projectId)
                .param("promptId", promptId)
                .params(beforeVersion == null ? Map.of() : Map.of("before", beforeVersion))
                .param("limit", limit)
                .query((rs, n) -> new PromptVersionSummaryRow(
                        rs.getString("id"),
                        rs.getInt("version"),
                        rs.getString("commit_message"),
                        rs.getString("created_by"),
                        CreateVia.fromWire(rs.getString("created_via")),
                        instant(rs, "created_at")))
                .list();
    }

    public Optional<PromptVersionRow> findVersion(String projectId, String promptId, int version) {
        return jdbc.sql("SELECT " + VERSION_COLS + " FROM prompt_version"
                        + " WHERE project_id = :pid AND prompt_id = :promptId AND version = :version")
                .param("pid", projectId)
                .param("promptId", promptId)
                .param("version", version)
                .query((rs, n) -> version(rs))
                .optional();
    }

    /**
     * The fetch in one statement, so the version, its labels and the highest number come from one snapshot: separate
     * reads let a label move or a delete land between them. Empty when the name is not a prompt of this project; a
     * prompt with no version matching {@code selector} comes back with a {@code null} version.
     */
    public Optional<PromptResolveRow> resolve(String projectId, String name, PromptSelector selector) {
        Map<String, Object> params = new HashMap<>();
        params.put("pid", projectId);
        params.put("name", name);
        String pick =
                switch (selector) {
                    case PromptSelector.Version v -> {
                        params.put("version", v.number());
                        yield " AND v.version = :version";
                    }
                    case PromptSelector.Label l -> {
                        params.put("label", l.label());
                        yield " AND v.id = (SELECT l.version_id FROM prompt_label l WHERE l.project_id = p.project_id"
                                + " AND l.prompt_id = p.id AND l.label = :label)";
                    }
                    case PromptSelector.Latest _ -> " ORDER BY v.version DESC LIMIT 1";
                };
        String sql = "SELECT " + PROMPT_COLS + ", sel.id AS v_id, sel.version AS v_version, sel.body AS v_body,"
                + " sel.config AS v_config, sel.commit_message AS v_commit_message, sel.created_by AS v_created_by,"
                + " sel.created_via AS v_created_via, sel.created_at AS v_created_at,"
                + " (SELECT COALESCE(array_agg(l.label), '{}') FROM prompt_label l WHERE l.version_id = sel.id)"
                + " AS v_labels,"
                + " (SELECT COALESCE(MAX(x.version), 0) FROM prompt_version x WHERE x.project_id = p.project_id"
                + " AND x.prompt_id = p.id) AS max_version"
                + " FROM prompt p LEFT JOIN LATERAL (SELECT v.* FROM prompt_version v"
                + " WHERE v.project_id = p.project_id AND v.prompt_id = p.id" + pick + ") sel ON true"
                + " WHERE p.project_id = :pid AND p.name = :name";
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> {
                    PromptRow prompt = prompt(rs);
                    PromptVersionRow version = rs.getString("v_id") == null
                            ? null
                            : new PromptVersionRow(
                                    rs.getString("v_id"),
                                    prompt.projectId(),
                                    prompt.id(),
                                    rs.getInt("v_version"),
                                    rs.getString("v_body"),
                                    rs.getString("v_config"),
                                    rs.getString("v_commit_message"),
                                    rs.getString("v_created_by"),
                                    CreateVia.fromWire(rs.getString("v_created_via")),
                                    instant(rs, "v_created_at"));
                    List<String> labels = List.copyOf(new TreeSet<>(
                            List.of((String[]) rs.getArray("v_labels").getArray())));
                    return new PromptResolveRow(prompt, version, labels, rs.getInt("max_version"));
                })
                .optional();
    }

    public boolean deleteVersion(String projectId, String promptId, int version) {
        return jdbc.sql("DELETE FROM prompt_version"
                                + " WHERE project_id = :pid AND prompt_id = :promptId AND version = :version")
                        .param("pid", projectId)
                        .param("promptId", promptId)
                        .param("version", version)
                        .update()
                > 0;
    }

    /** Versions and labels go with it by cascade. */
    public boolean deletePrompt(String projectId, String id) {
        return jdbc.sql("DELETE FROM prompt WHERE project_id = :pid AND id = :id")
                        .param("pid", projectId)
                        .param("id", id)
                        .update()
                > 0;
    }

    /** {@code prefix} ends with {@code /}; {@code starts_with}, because {@code _} is a LIKE wildcard. */
    public int deleteFolder(String projectId, String prefix) {
        return jdbc.sql("DELETE FROM prompt WHERE project_id = :pid AND starts_with(name, :prefix)")
                .param("pid", projectId)
                .param("prefix", prefix)
                .update();
    }

    /**
     * With a JSON filter, a prompt is listed when one of its versions meets every condition, and {@code versions} and
     * the last config come from the matching versions only; {@code highest_version} is always the prompt's own.
     */
    public List<PromptListRow> list(String projectId, PromptListQuery q, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String matches = versionFilter(q.filter(), params);
        String order = q.order() == PromptListQuery.Order.NAME ? "p.name" : "p.updated_at DESC, p.name";
        String sql = "SELECT " + PROMPT_COLS + ", s.versions, s.highest_version,"
                + " COALESCE(lc.config, '{}'::jsonb) AS last_config FROM prompt p"
                + " JOIN LATERAL (SELECT COALESCE(array_agg(v.version ORDER BY v.version) FILTER (WHERE " + matches
                + "), '{}') AS versions, COALESCE(MAX(v.version), 0) AS highest_version FROM prompt_version v"
                + " WHERE v.project_id = p.project_id AND v.prompt_id = p.id) s ON true"
                + " LEFT JOIN LATERAL (SELECT v.config FROM prompt_version v"
                + " WHERE v.project_id = p.project_id AND v.prompt_id = p.id AND " + matches
                + " ORDER BY v.version DESC LIMIT 1) lc ON true"
                + where(projectId, q, params) + " ORDER BY " + order + " LIMIT :limit OFFSET :offset";
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) -> new PromptListRow(
                        prompt(rs),
                        versions(rs.getArray("versions")),
                        rs.getString("last_config"),
                        rs.getInt("highest_version")))
                .list();
    }

    public long count(String projectId, PromptListQuery q) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM prompt p" + where(projectId, q, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * The folders one level below {@code q.folder()} (the root when null) that hold a prompt matching {@code q}, by
     * path, each with the count of matching prompts anywhere under it and the newest one's update time.
     */
    public List<PromptFolderRow> childFolders(String projectId, PromptListQuery q) {
        Map<String, Object> params = new HashMap<>();
        String folder = q.folder();
        String prefix = folder == null ? "" : folder + "/";
        params.put("levelPrefix", prefix);
        String sql = "SELECT :levelPrefix || split_part(" + REST_OF_NAME + ", '/', 1) AS path, count(*) AS prompts,"
                + " max(p.updated_at) AS updated_at FROM prompt p" + where(projectId, q, params)
                + " AND strpos(" + REST_OF_NAME + ", '/') > 0 GROUP BY 1 ORDER BY 1";
        params.put("levelOffset", prefix.codePointCount(0, prefix.length()) + 1);
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) ->
                        new PromptFolderRow(rs.getString("path"), rs.getLong("prompts"), instant(rs, "updated_at")))
                .list();
    }

    public List<String> labelNames(String projectId) {
        return jdbc.sql("SELECT DISTINCT label FROM prompt_label WHERE project_id = :pid ORDER BY label")
                .param("pid", projectId)
                .query(String.class)
                .list();
    }

    public List<String> tagNames(String projectId) {
        return jdbc.sql("SELECT DISTINCT t.tag FROM prompt p, jsonb_array_elements_text(p.tags) AS t(tag)"
                        + " WHERE p.project_id = :pid ORDER BY t.tag")
                .param("pid", projectId)
                .query(String.class)
                .list();
    }

    private static String where(String projectId, PromptListQuery q, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE p.project_id = :pid");
        params.put("pid", projectId);
        String name = q.name();
        if (name != null) {
            sql.append(" AND p.name = :name");
            params.put("name", PromptNames.lookupName(name));
        }
        String folder = q.folder();
        if (folder != null) {
            sql.append(" AND starts_with(p.name, :folderPrefix)");
            params.put("folderPrefix", folder + "/");
        }
        if (q.direct()) {
            String prefix = folder == null ? "" : folder + "/";
            sql.append(" AND strpos(").append(REST_OF_NAME).append(", '/') = 0");
            params.put("levelOffset", prefix.codePointCount(0, prefix.length()) + 1);
        }
        String search = q.q();
        if (search != null) {
            sql.append(" AND p.name ILIKE :q ESCAPE '\\'");
            params.put("q", "%" + escapeLike(search) + "%");
        }
        PromptType type = q.type();
        if (type != null) {
            sql.append(" AND p.type = :type");
            params.put("type", type.wire());
        }
        String tag = q.tag();
        if (tag != null) {
            sql.append(" AND jsonb_exists(p.tags, :tag)");
            params.put("tag", tag);
        }
        String label = q.label();
        if (label != null && !PromptNames.LATEST.equals(label)) {
            sql.append(" AND EXISTS (SELECT 1 FROM prompt_label l WHERE l.project_id = p.project_id"
                    + " AND l.prompt_id = p.id AND l.label = :label)");
            params.put("label", label);
        }
        Instant from = q.fromUpdatedAt();
        if (from != null) {
            sql.append(" AND p.updated_at >= :fromUpdatedAt");
            params.put("fromUpdatedAt", ts(from));
        }
        Instant to = q.toUpdatedAt();
        if (to != null) {
            sql.append(" AND p.updated_at <= :toUpdatedAt");
            params.put("toUpdatedAt", ts(to));
        }
        List<PromptListFilter.Condition> filter = q.filter();
        if (filter != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM prompt_version v WHERE v.project_id = p.project_id"
                            + " AND v.prompt_id = p.id AND ")
                    .append(versionFilter(filter, params))
                    .append(")");
        }
        return sql.toString();
    }

    /**
     * The JSON filter as one predicate over a version {@code v} of prompt {@code p}. Every column and operator was
     * checked against a fixed set by {@link PromptListFilter}, so each maps to a fixed fragment; every value is bound.
     */
    private static String versionFilter(@Nullable List<PromptListFilter.Condition> filter, Map<String, Object> params) {
        if (filter == null || filter.isEmpty()) return "true";
        List<String> parts = new ArrayList<>(filter.size());
        for (int i = 0; i < filter.size(); i++) parts.add(condition(filter.get(i), "f" + i, params));
        return "(" + String.join(" AND ", parts) + ")";
    }

    private static String condition(PromptListFilter.Condition c, String key, Map<String, Object> params) {
        return switch (c) {
            case PromptListFilter.TextMatch t -> text(textColumn(t.column()), t.operator(), t.value(), key, params);
            case PromptListFilter.OptionsMatch o -> {
                params.put(key, o.values().toArray(String[]::new));
                String in = textColumn(o.column()) + " = ANY(:" + key + ")";
                yield "any of".equals(o.operator()) ? in : "NOT (" + in + ")";
            }
            case PromptListFilter.NumberMatch m -> {
                params.put(key, m.value());
                yield "v.version " + m.operator() + " :" + key;
            }
            case PromptListFilter.TimeMatch t -> {
                params.put(key, ts(t.value()));
                yield ("createdAt".equals(t.column()) ? "v.created_at" : "p.updated_at") + " " + t.operator() + " :"
                        + key;
            }
            case PromptListFilter.ArrayMatch a when "labels".equals(a.column()) -> labels(a, key, params);
            case PromptListFilter.ArrayMatch a -> {
                params.put(key, a.values().toArray(String[]::new));
                yield switch (a.operator()) {
                    case "all of" -> "jsonb_exists_all(p.tags, :" + key + ")";
                    case "any of" -> "jsonb_exists_any(p.tags, :" + key + ")";
                    default -> "NOT jsonb_exists_any(p.tags, :" + key + ")";
                };
            }
            case PromptListFilter.ConfigMatch m -> {
                params.put(key + "k", m.key());
                yield switch (m.operator()) {
                    case "is set" -> "jsonb_exists(v.config, :" + key + "k)";
                    case "is not set" -> "NOT jsonb_exists(v.config, :" + key + "k)";
                    default -> text("(v.config ->> :" + key + "k)", m.operator(), m.value(), key, params);
                };
            }
        };
    }

    private static String textColumn(String column) {
        return switch (column) {
            case "id" -> "v.id";
            case "name" -> "p.name";
            default -> "p.type";
        };
    }

    /** Substring operators are a case-insensitive {@code ILIKE} on the escaped value, as in another prompt tool. */
    private static String text(String column, String operator, String value, String key, Map<String, Object> params) {
        String like = " ILIKE :" + key + " ESCAPE '\\'";
        return switch (operator) {
            case "is not empty" -> "(" + column + " IS NOT NULL AND " + column + " <> '')";
            case "=" -> {
                params.put(key, value);
                yield column + " = :" + key;
            }
            case "contains" -> {
                params.put(key, "%" + escapeLike(value) + "%");
                yield column + like;
            }
            case "does not contain" -> {
                params.put(key, "%" + escapeLike(value) + "%");
                yield "NOT (" + column + like + ")";
            }
            case "starts with" -> {
                params.put(key, escapeLike(value) + "%");
                yield column + like;
            }
            default -> {
                params.put(key, "%" + escapeLike(value));
                yield column + like;
            }
        };
    }

    /** A version's labels are its stored labels plus {@code latest} when it is the highest, which is never stored. */
    private static String labels(PromptListFilter.ArrayMatch a, String key, Map<String, Object> params) {
        Set<String> stored = new LinkedHashSet<>(a.values());
        boolean latest = stored.remove(PromptNames.LATEST);
        String isLatest = "v.version = (SELECT MAX(x.version) FROM prompt_version x"
                + " WHERE x.project_id = v.project_id AND x.prompt_id = v.prompt_id)";
        params.put(key, stored.toArray(String[]::new));
        params.put(key + "n", stored.size());
        List<String> parts = new ArrayList<>(2);
        if ("all of".equals(a.operator())) {
            if (!stored.isEmpty()) {
                parts.add("(SELECT count(*) FROM prompt_label l WHERE l.version_id = v.id AND l.label = ANY(:" + key
                        + ")) = :" + key + "n");
            }
            if (latest) parts.add(isLatest);
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (!stored.isEmpty()) {
            parts.add(
                    "EXISTS (SELECT 1 FROM prompt_label l WHERE l.version_id = v.id AND l.label = ANY(:" + key + "))");
        }
        if (latest) parts.add(isLatest);
        String any = "(" + String.join(" OR ", parts) + ")";
        return "any of".equals(a.operator()) ? any : "NOT " + any;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static PromptRow prompt(ResultSet rs) throws SQLException {
        return new PromptRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("name"),
                PromptType.fromWire(rs.getString("type")),
                PromptJson.readStrings(rs.getString("tags")),
                rs.getInt("last_version"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static PromptVersionRow version(ResultSet rs) throws SQLException {
        return new PromptVersionRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("prompt_id"),
                rs.getInt("version"),
                rs.getString("body"),
                rs.getString("config"),
                rs.getString("commit_message"),
                rs.getString("created_by"),
                CreateVia.fromWire(rs.getString("created_via")),
                instant(rs, "created_at"));
    }

    private static List<Integer> versions(Array array) throws SQLException {
        return List.of((Integer[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
