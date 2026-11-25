// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The small per-project numbers that fact rows use for a route (a trace name and call site pair) and for a version (a
 * {@code project_version_id}). A number never changes once given. A new number is the project's largest plus one,
 * taken under a per-project lock in its own short transaction, so the hit sweep and the daily job can register routes
 * at the same time and a caller's longer transaction does not hold the lock.
 */
@Repository
public class RouteRegistryRepository {

    public static final String UNATTRIBUTED = "__unattributed__";

    private final JdbcClient jdbc;
    private final TransactionTemplate ownTransaction;

    public RouteRegistryRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A NULL call site is stored as {@link #UNATTRIBUTED}; a NULL trace name is a route of its own. */
    public int routeNo(String projectId, @Nullable String traceName, @Nullable String callSiteId) {
        String callSite = callSiteId == null ? UNATTRIBUTED : callSiteId;
        return findRoute(projectId, traceName, callSite)
                .orElseGet(() -> register(
                        "end_user_route:" + projectId,
                        () -> findRoute(projectId, traceName, callSite)
                                .orElseGet(() -> insertRoute(projectId, traceName, callSite))));
    }

    /**
     * A NULL version is a version of its own. The stored first trace day, read as the deploy day, is the earliest day
     * any call has given.
     */
    public int versionNo(String projectId, @Nullable String projectVersionId, int firstTraceDay) {
        return touchVersion(projectId, projectVersionId, firstTraceDay)
                .orElseGet(() -> register(
                        "end_user_version:" + projectId,
                        () -> touchVersion(projectId, projectVersionId, firstTraceDay)
                                .orElseGet(() -> insertVersion(projectId, projectVersionId, firstTraceDay))));
    }

    private int register(String lockKey, IntSupplier findOrInsert) {
        Integer number = ownTransaction.execute(tx -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                    .param("key", lockKey)
                    .query()
                    .singleValue();
            return findOrInsert.getAsInt();
        });
        return Objects.requireNonNull(number);
    }

    private Optional<Integer> findRoute(String projectId, @Nullable String traceName, String callSite) {
        return jdbc.sql("""
                SELECT route_no FROM end_user_route
                 WHERE project_id = :pid AND trace_name IS NOT DISTINCT FROM CAST(:name AS text) AND call_site_id = :cs
                """)
                .param("pid", projectId)
                .param("name", traceName)
                .param("cs", callSite)
                .query(Integer.class)
                .optional();
    }

    private int insertRoute(String projectId, @Nullable String traceName, String callSite) {
        return jdbc.sql("""
                INSERT INTO end_user_route (project_id, route_no, trace_name, call_site_id)
                SELECT :pid, COALESCE(MAX(route_no), 0) + 1, CAST(:name AS text), :cs
                  FROM end_user_route WHERE project_id = :pid
             RETURNING route_no
                """)
                .param("pid", projectId)
                .param("name", traceName)
                .param("cs", callSite)
                .query(Integer.class)
                .single();
    }

    private Optional<Integer> touchVersion(String projectId, @Nullable String projectVersionId, int firstTraceDay) {
        return jdbc.sql("""
                UPDATE end_user_version SET first_trace_day = LEAST(first_trace_day, :day)
                 WHERE project_id = :pid AND project_version_id IS NOT DISTINCT FROM CAST(:version AS text)
             RETURNING version_no
                """)
                .param("pid", projectId)
                .param("version", projectVersionId)
                .param("day", firstTraceDay)
                .query(Integer.class)
                .optional();
    }

    private int insertVersion(String projectId, @Nullable String projectVersionId, int firstTraceDay) {
        return jdbc.sql("""
                INSERT INTO end_user_version (project_id, version_no, project_version_id, first_trace_day)
                SELECT :pid, COALESCE(MAX(version_no), 0) + 1, CAST(:version AS text), :day
                  FROM end_user_version WHERE project_id = :pid
             RETURNING version_no
                """)
                .param("pid", projectId)
                .param("version", projectVersionId)
                .param("day", firstTraceDay)
                .query(Integer.class)
                .single();
    }
}
