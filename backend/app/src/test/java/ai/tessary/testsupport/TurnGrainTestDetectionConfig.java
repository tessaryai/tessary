// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.db.LiquibaseConfig;
import ai.tessary.detection.DetectionTable;
import ai.tessary.detection.DetectionTable.Grain;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Gives the six turn-grain {@code @SpringBootTest}s that grant {@code Capability.FRUSTRATION} a
 * table to write into, without any open changelog ever creating one.
 *
 * <p><b>Why this exists at all.</b> Frustration is a paid, TRACE-grain classifier
 * (see {@code tessary-paid/frustration}'s {@code DetectionTable} registration), and every OPEN
 * detection table has {@code subject_span_id NOT NULL} (secret_leak / malformed_output /
 * user_classifier detection) — so nothing open can hold a turn-grain row, and these six tests, which
 * exercise the open {@code EncoderDetector} through {@link StubEncoderScorerConfig} at TURN grain,
 * would have nowhere to write. Rather than relax an open table's constraint (which would let a
 * span-grain classifier write a nonsensical NULL span id), this test-only config registers its own
 * {@link DetectionTable} pointed at a scratch table this config itself creates.
 *
 * <p><b>This IS the open side's end-to-end proof of the off-classpath {@code DetectionTable} path.</b>
 * {@code zz_test_turn_detection} is a table no open changelog creates, registered by a bean outside
 * {@code OpenDetectionTables}, written by the open {@code ClassifierDetectionWriteRepository}, and
 * read back through {@code DetectionTableRegistry#unionSql()} — exactly the shape a paid classifier
 * module uses in a running backend, minus the separate Liquibase changelog (this is test-JVM DDL, in
 * no changelog and no lane of the ordering contract).
 *
 * <p>{@code createTurnDetectionTable} is {@code @DependsOn(LiquibaseConfig.BEAN_NAME)} so its
 * {@code CREATE TABLE} runs strictly after the open master has applied — the same ordering contract
 * {@code PaidDbAutoConfiguration}'s real {@code SpringLiquibase} bean relies on, proved here with a
 * plain JDBC bean instead of a second Liquibase changelog because this table is disposable test
 * fixture, not a schema the platform ships.
 *
 * <p>{@code LIKE secret_leak_detection INCLUDING DEFAULTS INCLUDING CONSTRAINTS} copies every column
 * and constraint verbatim — including {@code subject_span_id NOT NULL}, which a TRACE-grain row never
 * populates, so the very next statement drops it. The unique index on
 * {@code (project_id, classifier_id, subject_trace_id)} is this table's OWN idempotency key (a
 * turn-grain detection is unique per trace, not per span), matching frustration's real trace-grain
 * detection table shape rather than the span-grain table this LIKEs from.
 */
@TestConfiguration
public class TurnGrainTestDetectionConfig {

    public static final String TABLE = "zz_test_turn_detection";

    @Bean
    public DetectionTable frustrationTurnGrainTestTable() {
        return new DetectionTable(BuiltInDetector.Kind.FRUSTRATION, TABLE, Grain.TRACE);
    }

    @Bean
    @DependsOn(LiquibaseConfig.BEAN_NAME)
    public Object createTurnDetectionTable(JdbcClient jdbc) {
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + TABLE
                        + " (LIKE secret_leak_detection INCLUDING DEFAULTS INCLUDING CONSTRAINTS)")
                .update();
        jdbc.sql("ALTER TABLE " + TABLE + " ALTER COLUMN subject_span_id DROP NOT NULL")
                .update();
        jdbc.sql("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + TABLE + "_subject ON " + TABLE
                        + " (project_id, classifier_id, subject_trace_id)")
                .update();
        // A marker bean, not a real collaborator: its only job is to run the DDL above during
        // singleton instantiation, ordered after the open Liquibase bean by @DependsOn. Nothing reads
        // this value; it exists because a @Bean method must return something.
        return new Object();
    }
}
