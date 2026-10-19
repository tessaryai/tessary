// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MetricsLabelRepository {

    private final JdbcClient jdbc;

    public MetricsLabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Id to use_case for the ids given; ids with no use_case are absent. */
    public Map<String, String> callSiteLabels(String projectId, Collection<String> ids) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("SELECT id, use_case FROM call_site WHERE project_id = :pid AND id = ANY(:ids) AND use_case IS NOT"
                        + " NULL")
                .param("pid", projectId)
                .param("ids", ids.toArray(String[]::new))
                .query(rs -> {
                    out.put(rs.getString("id"), rs.getString("use_case"));
                });
        return out;
    }
}
