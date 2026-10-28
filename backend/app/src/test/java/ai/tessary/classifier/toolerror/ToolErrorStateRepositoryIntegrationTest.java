// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The carried state's two learning columns against Postgres: what a sweep writes, and what a ruling clears. */
@SpringBootTest
record ToolErrorStateRepositoryIntegrationTest(
        TenantService tenants, ToolErrorStateRepository states, JdbcClient jdbc) {

    private static final String TOOL = "tool:search_docs";
    private static final Instant START = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    private static String hour(int h) {
        return START.plus(h, ChronoUnit.HOURS).toString();
    }

    private static ToolErrorRate rate(long calls, long failures) {
        ToolErrorRate r = new ToolErrorRate();
        r.addCounts(calls, failures);
        return r;
    }

    private String savedProject(String name) {
        String pid = TenantFixture.bootstrap(tenants, name).project().id();
        states.save(
                pid,
                new CarriedState(
                        TOOL,
                        new State(1.5, 0, hour(5), null, 40, 0),
                        rate(1000, 10),
                        hour(6),
                        "epoch-1",
                        null,
                        null,
                        null,
                        hour(4),
                        null),
                Instant.now().toString());
        return pid;
    }

    private CarriedState read(String pid) {
        return Objects.requireNonNull(states.byTool(pid).get(TOOL));
    }

    /** Written but never read, every pass would re-fold the hours between learned_through and the watermark. */
    @Test
    void save_roundTripsLearnedThrough() {
        String pid = savedProject("state-repo-roundtrip");

        CarriedState back = read(pid);
        assertEquals(new State(1.5, 0, hour(5), null, 40, 0), back.state());
        ToolErrorRate baseline = Objects.requireNonNull(back.baseline());
        assertEquals(1000, baseline.calls());
        assertEquals(10, baseline.failures());
        assertEquals(hour(6), back.watermarkBucket());
        assertEquals("epoch-1", back.stateEpoch());
        assertNull(back.pendingPinBy());
        assertNull(back.pendingPinAt());
        assertNull(back.resetAt());
        assertEquals(hour(4), back.learnedThrough(), "the newest hour learned, not the watermark");
        assertNull(back.learnBefore());
    }

    /** A row written before learned_through existed learned every hour it judged; reading null would re-learn them. */
    @Test
    void list_loadsANullLearnedThroughAsTheWatermark() {
        String pid = savedProject("state-repo-legacy");
        jdbc.sql("UPDATE tool_error_state SET learned_through = NULL WHERE project_id = :pid")
                .param("pid", pid)
                .update();

        assertEquals(hour(6), read(pid).learnedThrough());
    }

    /** A ruling that re-learns must lift the upgrade's fence, or the call site re-learns from nothing and stays silent. */
    @Test
    void resetAndRelearn_liftsTheLearningFenceAndForgetsLearnedThrough() {
        String pid = savedProject("state-repo-relearn");
        jdbc.sql("UPDATE tool_error_state SET learn_before = :at WHERE project_id = :pid")
                .param("at", hour(3))
                .param("pid", pid)
                .update();
        assertEquals(hour(3), read(pid).learnBefore());

        states.resetAndRelearn(
                pid, TOOL, null, "the old normal was wrong", Instant.now().toString());

        CarriedState after = read(pid);
        assertNull(after.learnBefore());
        assertNull(after.learnedThrough());
        assertNull(after.baseline());
        assertNotNull(after.resetAt());
    }
}
