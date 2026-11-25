// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.GRADE_CUT;
import static ai.tessary.endusers.judge.JudgeConstants.NEW_MAX_AGE_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.NEW_MAX_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.NEW_MAX_P_STOP;
import static ai.tessary.endusers.judge.JudgeConstants.OVERDUE_FLOOR;
import static ai.tessary.endusers.judge.JudgeConstants.STOP_CUT;

import ai.tessary.endusers.judge.PageNotes.Inputs;
import ai.tessary.endusers.judge.SeasonLevel.Season;
import ai.tessary.endusers.judge.StopStageRule.Decision;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.UserIdState;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The display states of spec 7.2 and the step of the judge that turns them into the funnel and the page notes. The
 * state order is v3's without the {@code at_risk} and {@code slowing} rows (level 2): stopped, then held, then new, then
 * active with the substate overdue or none. An ID that vanished in an ID change is active for 28 days and unknown after
 * that, and is in no stage. A refused page gives every ID the state unknown, and no stages and no stop stage.
 */
public final class States {

    private static final double NO_ANCHOR_STOP_CUT = 0.8; // spec 7.2
    private static final int CANNOT_FOLLOW_HELD_DAYS = 28; // [ref]

    private States() {}

    public static StateResult judge(
            Facts facts,
            Series series,
            Identity identity,
            IdChannelResult channel,
            TracerResult tracer,
            PageStateResult page,
            HitSet hits,
            SilenceResult silence,
            StopOdds odds,
            Guards guards) {
        boolean refused = !usable(page.state());
        Map<Integer, IdState> byId = byId(facts.asOfDay(), series, identity, channel, tracer, odds, refused);
        @Nullable StageCounts counts = refused ? null : Stages.counts(byId, odds, channel, silence, tracer);
        @Nullable
        Decision stop = counts == null
                ? null
                : StopStageRule.decide(
                        guards, facts.horizonDays(), odds, StopStageRule.coveredOneDayShare(facts, identity), counts);
        Season season = SeasonLevel.level(facts, series, identity, channel, silence, odds, hits);
        Inputs in = new Inputs(facts, series, identity, channel, tracer, page, silence, odds, guards);
        Map<SetAsideReason, Integer> setAside = new EnumMap<>(SetAsideReason.class);
        series.byId().keySet().forEach(u -> {
            SetAsideReason reason = identity.byId().get(u).setAside();
            if (reason != null) setAside.merge(reason, 1, Integer::sum);
        });
        return new StateResult(
                byId,
                counts,
                setAside,
                stop == null ? null : stop.state(),
                stop == null ? Map.of() : stop.numbers(),
                PageNotes.banners(in, season, stop),
                PageNotes.limits(in, counts, stop),
                PageNotes.trust(odds, tracer),
                season.mu());
    }

    /** The page shows numbers only in the states {@code ok} and {@code partial_coverage} (the reference's "usable"). */
    static boolean usable(PageState state) {
        return state == PageState.OK || state == PageState.PARTIAL_COVERAGE;
    }

    static Map<Integer, IdState> byId(
            int asOfDay,
            Series series,
            Identity identity,
            IdChannelResult channel,
            TracerResult tracer,
            StopOdds odds,
            boolean refused) {
        boolean anchored = !odds.anchors().isEmpty();
        Map<Integer, IdState> byId = new TreeMap<>();
        series.byId().forEach((u, s) -> {
            IdIdentity id = identity.byId().get(u);
            IdOdds o = odds.byId().get(u);
            if (refused) {
                byId.put(u, new IdState(UserIdState.UNKNOWN, null, null, null, o.daysSilent(), id.setAside(), false));
            } else if (channel.cannotFollow().contains(u)) {
                UserIdState state = asOfDay - channel.censoredFrom().get(u) <= CANNOT_FOLLOW_HELD_DAYS
                        ? UserIdState.ACTIVE
                        : UserIdState.UNKNOWN;
                byId.put(u, new IdState(state, null, null, null, o.daysSilent(), id.setAside(), true));
            } else {
                byId.put(u, display(asOfDay, u, s, id, o, anchored, channel, tracer));
            }
        });
        return byId;
    }

    private static IdState display(
            int asOfDay,
            int u,
            IdSeries s,
            IdIdentity id,
            IdOdds o,
            boolean anchored,
            IdChannelResult channel,
            TracerResult tracer) {
        double p = o.pStop();
        UserIdState state;
        @Nullable Substate substate = null;
        @Nullable Grade grade = null;
        Double pCalendar = o.pStopCalendar();
        boolean stopped = anchored ? p >= STOP_CUT : o.pNominal() >= NO_ANCHOR_STOP_CUT;
        if (stopped) {
            state = UserIdState.STOPPED;
            grade = p >= GRADE_CUT ? Grade.STOPPED : Grade.PROBABLY_STOPPED;
            if (o.unattended()) grade = Grade.UNATTENDED;
            if (!anchored) grade = Grade.NOT_YET_CHECKED;
        } else if (id.covered() && pCalendar != null && pCalendar >= OVERDUE_FLOOR) {
            state = UserIdState.ACTIVE;
            substate = Substate.HELD;
        } else if (s.days().size() <= NEW_MAX_DAYS
                && asOfDay - s.firstSeen() <= NEW_MAX_AGE_DAYS
                && !s.leftCensored()
                && !channel.bornInChange().contains(u)
                && !tracer.bornAfterContinued().contains(u)
                && p < NEW_MAX_P_STOP) {
            state = UserIdState.NEW;
        } else {
            state = UserIdState.ACTIVE;
            if (p >= OVERDUE_FLOOR) substate = Substate.OVERDUE;
        }
        return new IdState(
                state,
                substate,
                grade,
                id.covered() ? Stages.stage(state, substate, grade) : null,
                o.daysSilent(),
                id.setAside(),
                false);
    }
}
