// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.GROUP_BANNER_ID_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.GROUP_MIN_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_YOUNG_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.ID_CHANGE_YOUNG_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_AGGREGATE_ID_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_COVERED_PARTIAL;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_COVERED_REFUSE;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_RECENT_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_SHARE_PARTIAL;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_IDENT_SHARE_REFUSE;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_NOT_SEEN_AGAIN;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_RELAX_RUNS;
import static ai.tessary.endusers.judge.JudgeConstants.PAGE_TOKEN_LINK_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.REFUSAL_NAMED_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.TOO_EARLY_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.TOO_EARLY_IDS;

import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The page state of spec 3.5 with the level 2 memory. The rules run in the order of the spec table and the first that
 * applies sets the state; a row with more than one condition takes its reason from the first condition that holds and
 * gives all the row's numbers, except {@code no_user_id}, which gives only the share that fired, the share across all
 * history first. A refusal names its heavy IDs only when heavy IDs are its reason; its {@code hours_per_day} and
 * {@code sessions_per_day} are the medians over the heavy IDs of each ID's median day (spec 3.1), the sessions over
 * the IDs with a session day only. The W8 switch and the
 * {@code ids_stable_accounts} declaration are not coded: the tracer rows always act.
 */
public final class PageStateRule {

    private PageStateRule() {}

    public static PageStateResult decide(
            Facts facts,
            Series series,
            Identity identity,
            Map<Integer, Flags> flags,
            IdChannelResult channel,
            TracerResult tracer) {
        Rule rule = rule(facts.asOfDay(), series, identity, flags, channel, tracer);
        PageState shown = remembered(rule.state(), facts.pageStateMemory());
        boolean groupBanner = identity.pdGroupHint() + identity.pdAggregate() >= GROUP_BANNER_ID_DAYS;
        if (shown != rule.state()) {
            return new PageStateResult(shown, rule.state(), null, Map.of(), groupBanner, List.of());
        }
        List<Integer> named = rule.reason() == PageStateReason.HEAVY_IDS ? heavyIds(identity, flags) : List.of();
        return new PageStateResult(shown, rule.state(), rule.reason(), rule.numbers(), groupBanner, named);
    }

    /**
     * The state to show, from the state the rules gave and the rule states of earlier runs (oldest first). A stricter
     * state applies at once, and so does a change from one refusal to another; a looser state applies only when the
     * last run's rules gave it too. The state shown last run is read back from the last two rule states, and a run that
     * computed no state leaves no memory.
     */
    static PageState remembered(PageState rule, List<PageState> memory) {
        List<PageState> states = memory.stream().filter(s -> rank(s) >= 0).toList();
        if (states.isEmpty()) return rule;
        PageState last = states.getLast();
        PageState before = states.size() >= PAGE_RELAX_RUNS ? states.get(states.size() - PAGE_RELAX_RUNS) : last;
        PageState shownLast = rank(last) >= rank(before) ? last : before;
        if (rank(rule) >= rank(shownLast)) return rule;
        return last == rule ? rule : shownLast;
    }

    private static Rule rule(
            int asOfDay,
            Series series,
            Identity identity,
            Map<Integer, Flags> flags,
            IdChannelResult channel,
            TracerResult tracer) {
        double identShare = identity.identShare();
        double identShare28 = identity.identShare28();
        if (identShare < PAGE_IDENT_SHARE_REFUSE) {
            return new Rule(PageState.NO_USER_ID, PageStateReason.LOW_ID_SHARE, Map.of("ident_share", identShare));
        }
        if (identShare28 < PAGE_IDENT_SHARE_REFUSE) {
            return new Rule(
                    PageState.NO_USER_ID, PageStateReason.LOW_ID_SHARE_28, Map.of("ident_share_28", identShare28));
        }

        int span = asOfDay - series.t0Id();
        int ids = series.byId().size();
        boolean shortHistory = span < TOO_EARLY_DAYS;
        boolean fewIds = ids < TOO_EARLY_IDS;
        if (shortHistory || fewIds || youngIdChange(asOfDay, series, identity, channel)) {
            PageStateReason reason = shortHistory
                    ? PageStateReason.SHORT_HISTORY
                    : fewIds ? PageStateReason.FEW_IDS : PageStateReason.RECENT_ID_CHANGE;
            return new Rule(
                    PageState.TOO_EARLY,
                    reason,
                    new TreeMap<>(Map.of(
                            "span_days", (double) span,
                            "ids", (double) ids,
                            "span_days_needed", (double) TOO_EARLY_DAYS,
                            "ids_needed", (double) TOO_EARLY_IDS)));
        }

        if (identity.pdAggregate() >= PAGE_AGGREGATE_ID_DAYS) {
            List<Flags> heavy = identity.byId().entrySet().stream()
                    .filter(e -> e.getValue().aggregate())
                    .map(e -> flags.get(e.getKey()))
                    .toList();
            Map<String, Double> numbers =
                    new TreeMap<>(Map.of("ids", (double) heavy.size(), "id_day_share", identity.pdAggregate()));
            double[] hours = heavy.stream().mapToDouble(Flags::medianHours).toArray();
            if (hours.length > 0) numbers.put("hours_per_day", Stats.median(hours));
            double[] sessions = heavy.stream()
                    .map(Flags::medianSessions)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .toArray();
            if (sessions.length > 0) numbers.put("sessions_per_day", Stats.median(sessions));
            return new Rule(PageState.IDS_UNRELIABLE, PageStateReason.HEAVY_IDS, numbers);
        }

        if (tracer.verdict() == TracerVerdict.IDS_REPLACED) {
            return new Rule(
                    PageState.IDS_UNRELIABLE,
                    PageStateReason.IDS_REPLACED,
                    new TreeMap<>(Map.of("ended", (double) tracer.ended(), "continued", (double) tracer.continued())));
        }

        GroupStats app = identity.app();
        Double returnLater = app.returnLater();
        boolean notSeenAgain = app.old() >= GROUP_MIN_IDS && returnLater != null && returnLater < PAGE_NOT_SEEN_AGAIN;
        if (identity.pdCovered() < PAGE_COVERED_REFUSE || notSeenAgain) {
            Map<String, Double> numbers = new TreeMap<>(
                    Map.of("covered_share", identity.pdCovered(), "token_link_share", identity.linkShare()));
            if (returnLater != null) numbers.put("return_later", returnLater);
            return new Rule(
                    PageState.IDS_UNRELIABLE,
                    identity.linkShare() >= PAGE_TOKEN_LINK_SHARE
                            ? PageStateReason.IDS_COPY_SESSION_TOKEN
                            : PageStateReason.IDS_NOT_SEEN_AGAIN,
                    numbers);
        }

        @Nullable PageStateReason partial = null;
        if (identity.pdCovered() < PAGE_COVERED_PARTIAL) partial = PageStateReason.IDS_SET_ASIDE;
        else if (identShare < PAGE_IDENT_SHARE_PARTIAL) partial = PageStateReason.LOW_ID_SHARE;
        else if (channel.idLoss() != null) partial = PageStateReason.ID_LOSS;
        else if (channel.idChange() != null) partial = PageStateReason.ID_CHANGE;
        else if (tracer.verdict() == TracerVerdict.SOME_IDS_REPLACED) partial = PageStateReason.SOME_IDS_REPLACED;
        if (partial != null) {
            Map<String, Double> numbers =
                    new TreeMap<>(Map.of("covered_share", identity.pdCovered(), "ident_share", identShare));
            if (tracer.verdict() == TracerVerdict.SOME_IDS_REPLACED) {
                numbers.put("ended", (double) tracer.ended());
                numbers.put("continued", (double) tracer.continued());
            }
            return new Rule(PageState.PARTIAL_COVERAGE, partial, numbers);
        }
        return new Rule(PageState.OK, null, Map.of());
    }

    /**
     * An ID change not solved by aliases, under 21 days old, whose vanished and born IDs are at least half of the covered
     * IDs with a series day in the last 28 days.
     */
    private static boolean youngIdChange(int asOfDay, Series series, Identity identity, IdChannelResult channel) {
        IdChange change = channel.idChange();
        if (change == null || change.firstDay() == null) return false;
        long active = identity.byId().entrySet().stream()
                .filter(e -> e.getValue().covered()
                        && series.byId().get(e.getKey()).days().getLast() >= asOfDay - PAGE_IDENT_RECENT_DAYS)
                .count();
        if (active == 0) return false;
        for (IdChangeEvent event : change.events()) {
            if (!event.solvedByAliases()
                    && asOfDay - event.day() < ID_CHANGE_YOUNG_DAYS
                    && event.vanished().size() + event.born().size() >= ID_CHANGE_YOUNG_SHARE * active) {
                return true;
            }
        }
        return false;
    }

    /** Aggregate IDs by their ID-days (the {@code pd} weight), most first, ties by key index. */
    private static List<Integer> heavyIds(Identity identity, Map<Integer, Flags> flags) {
        return identity.byId().entrySet().stream()
                .filter(e -> e.getValue().aggregate())
                .map(Map.Entry::getKey)
                .sorted(Comparator.<Integer>comparingInt(u -> -flags.get(u).sizeDays())
                        .thenComparingInt(u -> u))
                .limit(REFUSAL_NAMED_IDS)
                .toList();
    }

    /** Refusals rank above partial coverage, which ranks above ok; a state no rule gives has no rank. */
    private static int rank(PageState state) {
        return switch (state) {
            case OK -> 0;
            case PARTIAL_COVERAGE -> 1;
            case TOO_EARLY, NO_USER_ID, IDS_UNRELIABLE -> 2;
            case NOT_COMPUTED, COLLECTING -> -1;
        };
    }

    private record Rule(PageState state, @Nullable PageStateReason reason, Map<String, Double> numbers) {}
}
