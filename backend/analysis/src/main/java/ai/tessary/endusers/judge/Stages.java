// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.STOPPED_RANGE_Z;

import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * The funnel of board P1 (final-plan 3.3, decision Q7). Active holds active IDs with no substate and new IDs; Silent,
 * not judged holds overdue and held IDs and stops of grade unattended (the person is silent while automation runs);
 * Probably stopped holds the other stopped IDs. The counted IDs are the covered IDs that can be followed, the ones with
 * a stage. The expected stop count and its range are spec 7.2's tile {@code stopped_expected}: the sum of
 * {@code p_stop}, plus or minus 1.64 standard deviations, widened to the ID-loss range, the held range and the tracer
 * range.
 */
public final class Stages {

    private Stages() {}

    static Stage stage(UserIdState state, @Nullable Substate substate, @Nullable Grade grade) {
        if (grade == Grade.UNATTENDED || substate != null) return Stage.SILENT;
        return state == UserIdState.STOPPED ? Stage.STOPPED : Stage.ACTIVE;
    }

    public static StageCounts counts(
            Map<Integer, IdState> byId,
            StopOdds odds,
            IdChannelResult channel,
            SilenceResult silence,
            TracerResult tracer) {
        int active = 0;
        int newIds = 0;
        int silent = 0;
        int held = 0;
        int stopped = 0;
        double expected = 0;
        double variance = 0;
        double calibrated = 0;
        for (Map.Entry<Integer, IdState> e : byId.entrySet()) {
            IdState s = e.getValue();
            Stage stage = s.stage();
            if (stage == null) continue;
            switch (stage) {
                case ACTIVE -> active++;
                case SILENT -> silent++;
                case STOPPED -> stopped++;
            }
            if (s.state() == UserIdState.NEW) newIds++;
            if (s.substate() == Substate.HELD) held++;
            IdOdds o = odds.byId().get(e.getKey());
            expected += o.pStop();
            variance += o.pStop() * (1 - o.pStop());
            calibrated += o.pCalibrated();
        }
        double sd = STOPPED_RANGE_Z * Math.sqrt(variance);
        double low = Math.max(0, expected - sd);
        double high = expected + sd;
        IdLoss idLoss = channel.idLoss();
        if (idLoss != null) high = Math.max(high, calibrated);

        Set<Integer> openMembers = new TreeSet<>();
        for (SilenceEvent event : silence.events()) {
            if (event.state() == SilenceState.OPEN) openMembers.addAll(event.members());
        }
        if (!openMembers.isEmpty()) {
            double clock = 0;
            double calendar = 0;
            for (int u : openMembers) {
                if (byId.get(u).stage() == null) continue;
                IdOdds o = odds.byId().get(u);
                clock += o.pStop();
                Double pCalendar = o.pStopCalendar();
                calendar += pCalendar == null ? o.pStop() : pCalendar;
            }
            high = Math.max(high, expected - clock + Math.max(calendar, clock));
        }
        Double replacedLow = tracer.replacedLow();
        if (replacedLow != null) low = Math.min(low, expected * replacedLow);
        return new StageCounts(active, newIds, silent, held, stopped, expected, low, high, active + silent + stopped);
    }
}
