// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class ClassifierSweeps {

    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(20);

    private ClassifierSweeps() {}

    public static void awaitDone(JdbcClient jdbc, String projectId, String classifierId) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (!"done".equals(status(jdbc, projectId, classifierId))) {
            if (Instant.now().isAfter(deadline)) {
                fail("the classifier sweep did not finish within " + DEADLINE);
            }
            LockSupport.parkNanos(POLL.toNanos());
        }
    }

    public static boolean sweptToNewestSpan(JdbcClient jdbc, String projectId, String classifierId) {
        return jdbc.sql("""
                        SELECT count(*) FROM job j
                        WHERE j.project_id = :pid AND j.kind = 'classifier' AND j.dedupe_key = :cid
                          AND j.status = 'done'
                          AND (j.cursor_at::timestamptz, split_part(j.cursor_id, ':', 1),
                               split_part(j.cursor_id, ':', 2))
                              >= (SELECT s.created_at, s.trace_id, s.id FROM span s WHERE s.project_id = :pid
                                  ORDER BY s.created_at DESC, s.trace_id DESC, s.id DESC LIMIT 1)
                        """)
                        .param("pid", projectId)
                        .param("cid", classifierId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    private static String status(JdbcClient jdbc, String projectId, String classifierId) {
        return jdbc.sql("SELECT status FROM job WHERE project_id = :pid AND kind = 'classifier' AND dedupe_key = :cid")
                .param("pid", projectId)
                .param("cid", classifierId)
                .query(String.class)
                .optional()
                .orElse("absent");
    }
}
