// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.JudgeConstants.FEW_COVERED_IDS;
import static ai.tessary.endusers.judge.JudgeConstants.F_MAX;
import static ai.tessary.endusers.judge.JudgeConstants.HOLD_DAYS;
import static ai.tessary.endusers.judge.JudgeConstants.LIMIT_LINES;
import static ai.tessary.endusers.judge.JudgeConstants.NAMED_FOUND_CUT;
import static ai.tessary.endusers.judge.JudgeConstants.NAMED_RIGHT_CUT;
import static ai.tessary.endusers.judge.JudgeConstants.ONE_DAY_SHARE;
import static ai.tessary.endusers.judge.JudgeConstants.UNATTENDED_LIMIT_SHARE;

import ai.tessary.endusers.judge.SeasonLevel.Season;
import ai.tessary.endusers.judge.StopStageRule.Decision;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.LimitCode;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SilenceState;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Banners, limit lines and trust numbers of spec 9 (reference lines 3937 to 4001) with the level 2 guards. The backend
 * gives codes and numbers by snake_case name; the frontend words them.
 *
 * <p>Banners, one per fact, in the order of {@link BannerCode}: {@code page_refusal} (the page's reason numbers);
 * {@code group_or_key_ids} ({@code ids}, {@code id_day_share}); {@code id_loss} ({@code onset_day}, {@code ids}: the IDs
 * that look stopped but probably lost their label); {@code id_change} ({@code first_day} when an event is unsolved,
 * {@code cannot_follow}); {@code tracer} ({@code ended}, {@code continued}); {@code shared_silence}, one for each event
 * the reference keeps, open ({@code start_day}, {@code share_silent}, {@code share_usual}, {@code ids},
 * {@code held_until_day}, {@code stopped_low}, {@code stopped_high}), a proven break that came back in the last 28 days
 * ({@code returned_day}) or a closed event of the last 56 days ({@code silent_since_day}); {@code season_level}
 * ({@code level}, {@code since_day}); {@code cannot_be_named_yet} (the stop stage's numbers); {@code outage} (guard
 * {@code outage-banner}: {@code day}, the last outage day, and {@code days}).
 *
 * <p>Limit lines hold for the condition they name; {@code ids} is the count of IDs a line concerns. A refused page has
 * none: its reason is the page state.
 */
public final class PageNotes {

    private static final int BREAK_BANNER_DAYS = 28; // [ref]
    private static final int CLOSED_BANNER_DAYS = 56; // [ref]
    private static final int SAME_EVENT_DAYS = 7; // [ref]
    private static final int NO_ANCHOR_DAYS = 28; // [ref]
    private static final int RETURN_CYCLE_SPAN_PARTS = 3; // spec 9

    private PageNotes() {}

    public record Inputs(
            Facts facts,
            Series series,
            Identity identity,
            IdChannelResult channel,
            TracerResult tracer,
            PageStateResult page,
            SilenceResult silence,
            StopOdds odds,
            Guards guards) {}

    public static List<Banner> banners(Inputs in, Season season, @Nullable Decision stop) {
        List<Banner> banners = new ArrayList<>();
        PageStateResult page = in.page();
        if (!States.usable(page.state())) banners.add(new Banner(BannerCode.PAGE_REFUSAL, page.reasonNumbers()));
        if (page.groupBanner()) banners.add(new Banner(BannerCode.GROUP_OR_KEY_IDS, groupNumbers(in.identity())));
        IdLoss idLoss = in.channel().idLoss();
        if (idLoss != null) {
            banners.add(new Banner(
                    BannerCode.ID_LOSS,
                    Map.of(
                            "onset_day",
                            (double) idLoss.onsetDay(),
                            "ids",
                            idLoss.phi() * idLoss.lostShare().size())));
        }
        IdChange idChange = in.channel().idChange();
        if (idChange != null) {
            Map<String, Double> numbers = new TreeMap<>();
            Integer firstDay = idChange.firstDay();
            if (firstDay != null) numbers.put("first_day", (double) firstDay);
            numbers.put("cannot_follow", (double) in.channel().cannotFollow().size());
            banners.add(new Banner(BannerCode.ID_CHANGE, numbers));
        }
        TracerResult tracer = in.tracer();
        if (tracer.verdict() == TracerVerdict.IDS_REPLACED || tracer.verdict() == TracerVerdict.SOME_IDS_REPLACED) {
            banners.add(new Banner(BannerCode.TRACER, Map.of("ended", (double) tracer.ended(), "continued", (double)
                    tracer.continued())));
        }
        banners.addAll(silenceBanners(in));
        if (season.banner()) {
            banners.add(new Banner(
                    BannerCode.SEASON_LEVEL, Map.of("level", season.mu(), "since_day", (double) season.sinceDay())));
        }
        if (stop != null && stop.state() == StopStageState.CANNOT_BE_NAMED_YET) {
            banners.add(new Banner(BannerCode.CANNOT_BE_NAMED_YET, stop.numbers()));
        }
        List<Integer> outageDays = in.facts().outageDays();
        if (in.guards().outageBanner() && !outageDays.isEmpty()) {
            int last = outageDays.stream().mapToInt(Integer::intValue).max().getAsInt();
            banners.add(
                    new Banner(BannerCode.OUTAGE, Map.of("day", (double) last, "days", (double) outageDays.size())));
        }
        banners.sort(Comparator.comparing(Banner::code));
        return banners;
    }

    public static List<LimitLine> limits(Inputs in, @Nullable StageCounts counts, @Nullable Decision stop) {
        if (counts == null || stop == null || !States.usable(in.page().state())) return List.of();
        return cut(lines(in, counts, stop));
    }

    /** Every limit line whose condition holds, in the reference's order. */
    static List<LimitLine> lines(Inputs in, StageCounts counts, Decision stop) {
        Identity identity = in.identity();
        StopOdds odds = in.odds();
        Series series = in.series();
        Set<Integer> covered = new TreeSet<>();
        series.byId().keySet().forEach(u -> {
            if (identity.byId().get(u).covered()) covered.add(u);
        });
        int nCovered = covered.size();
        int span = in.facts().asOfDay() - series.t0Id();
        List<LimitLine> lines = new ArrayList<>();

        if (in.page().groupBanner()) {
            lines.add(new LimitLine(
                    LimitCode.GROUP_OR_KEY_IDS,
                    groupNumbers(identity),
                    true,
                    groupNumbers(identity).get("ids").intValue()));
        }
        if (in.tracer().verdict() == TracerVerdict.CANNOT_TEST) {
            lines.add(new LimitLine(LimitCode.TRACER_CANNOT_TEST, Map.of(), true, 0));
        }
        if (nCovered < FEW_COVERED_IDS) {
            lines.add(new LimitLine(LimitCode.FEW_COVERED_IDS, Map.of("ids", (double) nCovered), true, nCovered));
        }
        if (stop.state() == StopStageState.CANNOT_BE_NAMED_YET) {
            lines.add(new LimitLine(
                    LimitCode.CANNOT_BE_NAMED_YET, stop.numbers(), false, (int) counts.stoppedExpected()));
        }
        Double namedRight = odds.namedRight();
        if (namedRight != null && namedRight < NAMED_RIGHT_CUT) {
            lines.add(new LimitLine(
                    LimitCode.NAMED_RIGHT_LOW, Map.of("named_right", namedRight), false, counts.stoppedNamed()));
        }
        Double namedFound = odds.namedFound();
        if (namedFound != null && namedFound < NAMED_FOUND_CUT) {
            lines.add(new LimitLine(LimitCode.NAMED_FOUND_LOW, Map.of("named_found", namedFound), false, (int)
                    counts.stoppedExpected()));
        }
        if (odds.anchors().isEmpty()) {
            lines.add(new LimitLine(
                    LimitCode.NO_ANCHOR,
                    Map.of("days_needed", (double) Math.max(0, NO_ANCHOR_DAYS - span)),
                    false,
                    nCovered));
        }
        if (in.facts().storedRouteClasses().lenient()) {
            lines.add(new LimitLine(LimitCode.LENIENT_BASIS, Map.of(), false, nCovered));
        }
        long unattended =
                odds.byId().values().stream().filter(IdOdds::unattended).count()
                        + covered.stream()
                                .filter(u -> series.byId().get(u).basis() == ActivityBasis.UNATTENDED)
                                .count();
        if (unattended > UNATTENDED_LIMIT_SHARE * Math.max(1, nCovered)) {
            lines.add(new LimitLine(LimitCode.UNATTENDED_SHARE, Map.of("ids", (double) unattended), false, nCovered));
        }
        IdLoss idLoss = in.channel().idLoss();
        if (idLoss != null) {
            int v = idLoss.lostShare().size();
            lines.add(new LimitLine(LimitCode.ID_LOSS, Map.of("ids", (double) v), false, v));
        }
        if (in.channel().idChange() != null) {
            int lost = in.channel().cannotFollow().size();
            lines.add(new LimitLine(LimitCode.ID_CHANGE, Map.of("ids", (double) lost), false, lost));
        }
        Set<Integer> silent = new TreeSet<>();
        for (SilenceEvent event : in.silence().events()) {
            if (event.state() == SilenceState.OPEN || event.state() == SilenceState.NOT_A_BREAK) {
                silent.addAll(event.members());
            }
        }
        if (!silent.isEmpty()) {
            lines.add(new LimitLine(
                    LimitCode.SHARED_SILENCE, Map.of("ids", (double) silent.size()), false, silent.size()));
        }
        LimitLine oneDay = oneDayLine(in, series.byId().size());
        if (oneDay != null) lines.add(oneDay);
        boolean accountEvent = in.silence().events().stream()
                .anyMatch(e -> e.scope().kind() == ScopeKind.CLUSTER
                        && (e.state() == SilenceState.NOT_A_BREAK || e.state() == SilenceState.TRAFFIC_ENDED));
        if (accountEvent) lines.add(new LimitLine(LimitCode.ACCOUNT_EVENT, Map.of(), false, nCovered));
        if (odds.f() >= F_MAX) {
            lines.add(new LimitLine(
                    LimitCode.RETURN_CYCLE_TOO_LONG,
                    Map.of("days", (double) Math.max(1, span / RETURN_CYCLE_SPAN_PARTS)),
                    false,
                    nCovered));
            if (in.guards().cannotTellYet()) {
                lines.add(new LimitLine(LimitCode.REPEAT_CONTACT, Map.of(), false, nCovered));
            }
        }
        List<Integer> gaps = in.facts().gapDays();
        if (!gaps.isEmpty()) {
            lines.add(new LimitLine(
                    LimitCode.FACTS_NOT_KEPT,
                    Map.of(
                            "from_day",
                                    (double) gaps.stream()
                                            .mapToInt(Integer::intValue)
                                            .min()
                                            .getAsInt(),
                            "to_day",
                                    (double) gaps.stream()
                                            .mapToInt(Integer::intValue)
                                            .max()
                                            .getAsInt(),
                            "days", (double) gaps.size()),
                    false,
                    nCovered));
        }
        return lines;
    }

    /** The never-cut lines in order, then at most 4 others by the count of IDs they concern; ties keep their order. */
    static List<LimitLine> cut(List<LimitLine> lines) {
        List<LimitLine> kept =
                new ArrayList<>(lines.stream().filter(LimitLine::neverCut).toList());
        lines.stream()
                .filter(l -> !l.neverCut())
                .sorted(Comparator.comparingInt(LimitLine::ids).reversed())
                .limit(LIMIT_LINES)
                .forEach(kept::add);
        return List.copyOf(kept);
    }

    /**
     * The trust numbers of spec 6.2: none without a past check or when some IDs are replaced; with the caveat "this
     * checks IDs, not people" when the tracer cannot test.
     */
    public static @Nullable TrustNumbers trust(StopOdds odds, TracerResult tracer) {
        Double namedRight = odds.namedRight();
        if (namedRight == null || tracer.verdict() == TracerVerdict.SOME_IDS_REPLACED) return null;
        return new TrustNumbers(namedRight, odds.namedFound(), tracer.verdict() == TracerVerdict.CANNOT_TEST);
    }

    private static Map<String, Double> groupNumbers(Identity identity) {
        long ids =
                identity.byId().values().stream().filter(IdIdentity::groupHint).count()
                        + identity.byId().values().stream()
                                .filter(IdIdentity::aggregate)
                                .count();
        return Map.of("ids", (double) ids, "id_day_share", identity.pdGroupHint() + identity.pdAggregate());
    }

    /**
     * The one-day line of spec 3.3. With the {@code mixed-id-formats} guard it is printed, never cut, when a format
     * that holds a covered ID has a one-day share of 0.3 or more; otherwise v3's line for an app with a single format
     * stands.
     */
    private static @Nullable LimitLine oneDayLine(Inputs in, int ids) {
        if (in.guards().mixedIdFormats()) {
            Double share = StopStageRule.coveredOneDayShare(in.facts(), in.identity());
            if (share != null && share >= ONE_DAY_SHARE) {
                return new LimitLine(LimitCode.ONE_DAY_SHARE, Map.of("one_day_share", share), true, ids);
            }
        }
        Set<String> formats = new TreeSet<>();
        in.series()
                .byId()
                .keySet()
                .forEach(
                        u -> formats.add(IdentityFlags.shapeKey(in.facts().ids().get(u))));
        Double app = in.identity().app().oneDayShare();
        if (formats.size() == 1 && app != null && app >= ONE_DAY_SHARE) {
            return new LimitLine(LimitCode.ONE_DAY_SHARE, Map.of("one_day_share", app), false, ids);
        }
        return null;
    }

    /**
     * One banner for each event the reference keeps: events largest first, each skipped when it starts within 7 days
     * of one already kept.
     */
    private static List<Banner> silenceBanners(Inputs in) {
        int asOf = in.facts().asOfDay();
        List<SilenceEvent> chosen = new ArrayList<>();
        in.silence().events().stream()
                .sorted(Comparator.comparingInt((SilenceEvent e) -> e.members().size())
                        .reversed())
                .forEach(e -> {
                    if (chosen.stream().noneMatch(c -> Math.abs(e.startDay() - c.startDay()) <= SAME_EVENT_DAYS)) {
                        chosen.add(e);
                    }
                });
        List<Banner> banners = new ArrayList<>();
        for (SilenceEvent e : chosen) {
            Integer endDay = e.endDay();
            if (e.state() == SilenceState.OPEN) {
                double clock = 0;
                double calendar = 0;
                int members = 0;
                for (int u : e.members()) {
                    IdIdentity id = in.identity().byId().get(u);
                    if (id == null || !id.covered()) continue;
                    IdOdds o = in.odds().byId().get(u);
                    members++;
                    clock += o.pStop();
                    Double pCalendar = o.pStopCalendar();
                    calendar += pCalendar == null ? o.pStop() : pCalendar;
                }
                Map<String, Double> numbers = new TreeMap<>();
                numbers.put("start_day", (double) e.startDay());
                numbers.put("share_silent", e.i());
                numbers.put("share_usual", e.i0());
                numbers.put("ids", (double) members);
                numbers.put("held_until_day", (double) (e.startDay() + HOLD_DAYS));
                numbers.put("stopped_low", clock);
                numbers.put("stopped_high", Math.max(clock, calendar));
                banners.add(new Banner(BannerCode.SHARED_SILENCE, numbers));
            } else if (e.state() == SilenceState.PROVEN_BREAK && endDay != null && asOf - endDay <= BREAK_BANNER_DAYS) {
                banners.add(new Banner(BannerCode.SHARED_SILENCE, Map.of("returned_day", (double) endDay)));
            } else if ((e.state() == SilenceState.NOT_A_BREAK || e.state() == SilenceState.TRAFFIC_ENDED)
                    && asOf - e.startDay() <= CLOSED_BANNER_DAYS) {
                banners.add(new Banner(BannerCode.SHARED_SILENCE, Map.of("silent_since_day", (double) e.startDay())));
            }
        }
        return banners;
    }
}
