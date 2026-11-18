// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code prompt} by name for ingest, which sits below the prompt feature and so cannot call its service. One
 * statement per batch on {@code ux_prompt_project_name}.
 */
@Repository
public class PromptLinkRepository {

    private final JdbcClient jdbc;

    public PromptLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Prompt id by name, for the names that name a prompt of this project; the others are absent. */
    public Map<String, String> idsByName(String projectId, Collection<String> names) {
        Map<String, String> ids = new HashMap<>();
        if (names.isEmpty()) return ids;
        jdbc.sql("SELECT name, id FROM prompt WHERE project_id = :pid AND name = ANY(:names)")
                .param("pid", projectId)
                .param("names", names.toArray(String[]::new))
                .query(rs -> {
                    ids.put(rs.getString("name"), rs.getString("id"));
                });
        return ids;
    }
}
