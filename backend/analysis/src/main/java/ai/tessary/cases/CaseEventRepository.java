// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence for {@link CaseEventRow} — the case's activity trail, append-only. */
@Repository
public class CaseEventRepository {

    private static final String COLS = "id, case_id, project_id, kind, actor, summary, detail, created_at";

    private final JdbcClient jdbc;

    public CaseEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One case's trail, oldest first — it reads as a story, so it is told in order. */
    public List<CaseEventRow> listByCase(String projectId, String caseId) {
        return jdbc.sql("SELECT " + COLS + " FROM eval_case_event WHERE project_id = :pid AND case_id = :caseId "
                        + "ORDER BY created_at, id")
                .param("pid", projectId)
                .param("caseId", caseId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public void append(
            String projectId,
            String caseId,
            String kind,
            @Nullable String actor,
            String summary,
            @Nullable String detailJson,
            Instant now) {
        jdbc.sql("""
                INSERT INTO eval_case_event (id, case_id, project_id, kind, actor, summary, detail, created_at)
                VALUES (:id, :caseId, :pid, :kind, :actor, :summary, CAST(:detail AS jsonb), :now)
                """)
                .param("id", Ids.ulid())
                .param("caseId", caseId)
                .param("pid", projectId)
                .param("kind", kind)
                .param("actor", actor)
                .param("summary", summary)
                .param("detail", detailJson)
                .param("now", now.toString())
                .update();
    }

    private static CaseEventRow map(ResultSet rs) throws SQLException {
        return new CaseEventRow(
                rs.getString("id"),
                rs.getString("case_id"),
                rs.getString("project_id"),
                rs.getString("kind"),
                rs.getString("actor"),
                rs.getString("summary"),
                rs.getString("detail"),
                rs.getString("created_at"));
    }
}
