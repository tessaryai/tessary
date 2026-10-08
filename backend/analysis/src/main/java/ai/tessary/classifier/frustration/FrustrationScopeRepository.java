// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The call sites a Frustration classifier scores, picked by a user. A call site not picked is never sent, and
 * nothing is sent until one is picked: one turn can call a router, the reply and a memory pass, and only the
 * call that answers the user is a conversation to judge. Its own table, because a catalog version bump rewrites
 * {@code classifier.config_json}.
 */
@Repository
public class FrustrationScopeRepository {

    private final JdbcClient jdbc;

    public FrustrationScopeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The picked call sites, in id order. */
    public Set<String> callSites(String projectId, String classifierId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT call_site_id FROM frustration_scope
                         WHERE project_id = :pid AND classifier_id = :cid
                         ORDER BY call_site_id""")
                .param("pid", projectId)
                .param("cid", classifierId)
                .query(String.class)
                .list());
    }

    /** Make {@code callSiteIds} the picked call sites, replacing the earlier picks. */
    @Transactional
    public void replace(String projectId, String classifierId, Collection<String> callSiteIds) {
        jdbc.sql("DELETE FROM frustration_scope WHERE project_id = :pid AND classifier_id = :cid")
                .param("pid", projectId)
                .param("cid", classifierId)
                .update();
        for (String callSiteId : new LinkedHashSet<>(callSiteIds)) {
            jdbc.sql("""
                            INSERT INTO frustration_scope (project_id, classifier_id, call_site_id)
                            VALUES (:pid, :cid, :callSite)""")
                    .param("pid", projectId)
                    .param("cid", classifierId)
                    .param("callSite", callSiteId)
                    .update();
        }
    }
}
