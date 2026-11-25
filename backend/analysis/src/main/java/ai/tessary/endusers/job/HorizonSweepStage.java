// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.endusers.facts.EndUserKeys;
import ai.tessary.endusers.facts.EndUserProjectRepository;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.retention.RetentionResolver;
import java.time.Instant;
import java.util.HexFormat;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The last stage of the daily job (plan 3.2, stage 5). Keeps the {@code H} days that end on the job's day: older fact
 * rows and IDs not seen in them leave. Then the readable text leaves each ID whose last trace is gone. Retention
 * deletes by start time, so only an ID last seen before the day the TTL cut falls in can have lost all its traces;
 * among those, an ID that still has a trace keeps its text (retention keeps pinned traces). A TTL of 0 deletes no
 * trace and clears no text.
 */
@Component
public class HorizonSweepStage implements EndUsersStage {

    private static final Logger log = LoggerFactory.getLogger(HorizonSweepStage.class);
    private static final HexFormat HEX = HexFormat.of();

    private final HorizonSweepRepository sweep;
    private final EndUserHistoryHorizon horizon;
    private final EndUserProjectRepository projects;
    private final RetentionResolver retention;

    public HorizonSweepStage(
            HorizonSweepRepository sweep,
            EndUserHistoryHorizon horizon,
            EndUserProjectRepository projects,
            RetentionResolver retention) {
        this.sweep = sweep;
        this.horizon = horizon;
        this.projects = projects;
        this.retention = retention;
    }

    @Override
    public String name() {
        return "sweep";
    }

    @Override
    public long run(String projectId, int day) {
        Instant began = Instant.now();
        OptionalInt keptDays = horizon.days(projectId);
        int facts = 0;
        int users = 0;
        if (keptDays.isPresent()) {
            int firstKeptDay = day + 1 - keptDays.getAsInt();
            facts = sweep.deleteFactsBefore(projectId, firstKeptDay);
            users = sweep.deleteUsersBefore(projectId, firstKeptDay);
        }
        int ttlDays = TraceTtl.days(retention, projectId);
        int texts = ttlDays > 0 ? clearTexts(projectId, day + 1 - ttlDays) : 0;
        StructuredLog.info(log, Markers.OPS, "endusers.sweep")
                .message("swept %d fact rows and %d user IDs; cleared %d ID texts", facts, users, texts)
                .field("project", projectId)
                .field("horizonDays", keptDays.isPresent() ? keptDays.getAsInt() : null)
                .field("factRows", facts)
                .field("users", users)
                .field("idTexts", texts)
                .durationMs(began)
                .log();
        return (long) facts + users + texts;
    }

    private int clearTexts(String projectId, int cutDay) {
        byte[] salt = projects.salt(projectId);
        Set<String> kept = new TreeSet<>();
        for (String raw : sweep.traceUserIdsBefore(projectId, cutDay)) {
            if (EndUserKeys.normalise(raw) != null) kept.add(HEX.formatHex(EndUserKeys.userKey(salt, raw)));
        }
        return sweep.clearIdTextBefore(projectId, cutDay, kept);
    }
}
