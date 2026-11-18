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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Newest first. */
    public List<PromptVersionRow> versionsOf(String projectId, String promptId) {
        return jdbc.sql("SELECT " + VERSION_COLS + " FROM prompt_version"
                        + " WHERE project_id = :pid AND prompt_id = :promptId ORDER BY version DESC")
                .param("pid", projectId)
                .param("promptId", promptId)
                .query((rs, n) -> version(rs))
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
     * The version a label points at, in one statement: reading the label and then the version by number lets a promote
     * and a delete of the old version land between the two reads.
     */
    public Optional<PromptVersionRow> findVersionByLabel(String projectId, String promptId, String label) {
        return jdbc.sql("SELECT v.id, v.project_id, v.prompt_id, v.version, v.body, v.config, v.commit_message,"
                        + " v.created_by, v.created_via, v.created_at FROM prompt_label l"
                        + " JOIN prompt_version v ON v.id = l.version_id"
                        + " WHERE l.project_id = :pid AND l.prompt_id = :promptId AND l.label = :label")
                .param("pid", projectId)
                .param("promptId", promptId)
                .param("label", label)
                .query((rs, n) -> version(rs))
                .optional();
    }

    /** The highest version, in one statement for the same reason as {@link #findVersionByLabel}. */
    public Optional<PromptVersionRow> findLatestVersion(String projectId, String promptId) {
        return jdbc.sql("SELECT " + VERSION_COLS + " FROM prompt_version"
                        + " WHERE project_id = :pid AND prompt_id = :promptId ORDER BY version DESC LIMIT 1")
                .param("pid", projectId)
                .param("promptId", promptId)
                .query((rs, n) -> version(rs))
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

    public List<PromptListRow> list(String projectId, PromptListQuery q, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String order = q.order() == PromptListQuery.Order.NAME ? "p.name" : "p.updated_at DESC, p.name";
        String sql = "SELECT " + PROMPT_COLS + ", s.versions, s.last_config FROM prompt p"
                + " JOIN LATERAL (SELECT COALESCE(array_agg(v.version ORDER BY v.version), '{}') AS versions,"
                + " COALESCE((array_agg(v.config ORDER BY v.version DESC))[1], '{}'::jsonb) AS last_config"
                + " FROM prompt_version v"
                + " WHERE v.project_id = p.project_id AND v.prompt_id = p.id) s ON true"
                + where(projectId, q, params) + " ORDER BY " + order + " LIMIT :limit OFFSET :offset";
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql(sql)
                .params(params)
                .query((rs, n) ->
                        new PromptListRow(prompt(rs), versions(rs.getArray("versions")), rs.getString("last_config")))
                .list();
    }

    public long count(String projectId, PromptListQuery q) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM prompt p" + where(projectId, q, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    private static String where(String projectId, PromptListQuery q, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE p.project_id = :pid");
        params.put("pid", projectId);
        String name = q.name();
        if (name != null) {
            sql.append(" AND p.name = :name");
            params.put("name", name);
        }
        String folder = q.folder();
        if (folder != null) {
            sql.append(" AND starts_with(p.name, :folderPrefix)");
            params.put("folderPrefix", folder + "/");
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
        return sql.toString();
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
