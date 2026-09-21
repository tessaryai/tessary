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
 * table to write into while {@code frustration_detection} is not yet registered for the events feed.
 *
 * <p>This test-only config registers its own {@link DetectionTable} pointed at a scratch table it
 * creates itself, shaped exactly like the shipped {@code frustration_detection} (trace grain,
 * nullable {@code subject_span_id}, {@code cleared_at}), proving the same off-classpath
 * {@code DetectionTable} registration path a real classifier module uses: a bean outside
 * {@code OpenDetectionTables}, written through the normal write repository, read back through
 * {@code DetectionTableRegistry#unionSql()}.
 *
 * <p>{@code createTurnDetectionTable} is {@code @DependsOn(LiquibaseConfig.BEAN_NAME)} so its
 * {@code CREATE TABLE} runs strictly after the master changelog has applied. It's a plain JDBC bean
 * rather than a second Liquibase changelog because this table is disposable test fixture, not
 * shipped schema.
 *
 * <p>{@code LIKE frustration_detection INCLUDING DEFAULTS INCLUDING CONSTRAINTS} copies every
 * column and CHECK constraint verbatim (not the foreign keys, which {@code LIKE} never copies). The
 * unique index on
 * {@code (project_id, classifier_id, subject_trace_id)} is this table's own idempotency key: a
 * turn-grain detection is unique per trace, not per span.
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
                        + " (LIKE frustration_detection INCLUDING DEFAULTS INCLUDING CONSTRAINTS)")
                .update();
        jdbc.sql("CREATE UNIQUE INDEX IF NOT EXISTS ux_" + TABLE + "_subject ON " + TABLE
                        + " (project_id, classifier_id, subject_trace_id)")
                .update();
        // A marker bean, not a real collaborator: its only job is to run the DDL above during
        // singleton instantiation, ordered after the Liquibase bean by @DependsOn. Nothing reads
        // this value; it exists because a @Bean method must return something.
        return new Object();
    }
}
