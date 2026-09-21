// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.classifier.toolerror.ToolErrorStateRepository;
import ai.tessary.classifier.toolerror.ToolErrorTrend;
import ai.tessary.classifier.toolerror.ToolErrorTrend.Spell;
import ai.tessary.classifier.worker.ClassifierCatchUp;
import ai.tessary.classifier.worker.ClassifierJobRow;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Frustration's rate test: per call site, whether the share of conversations frustrated with the agent has risen
 * above the rate that call site learned as its normal.
 *
 * <p><b>tool_error's engine, not a copy of it</b>, as Malformed Output uses it: a conversation is a Bernoulli
 * trial on the call site of its first scored turn, and it fails when it carries an uncleared frustration flag.
 * The hourly tallies ({@link FrustrationRateRepository}) are replayed through {@link ToolErrorTrend} on
 * {@link FrustrationConfig#engine()}. The reference is learned from the first {@code min_baseline_conversations}
 * and frozen; a call site that is frustrated from its first day learns that as its normal and is flagged only
 * for getting worse.
 *
 * <p><b>Rebuilt every pass</b> ({@link CarriedState#rebuilding}), because a conversation flagged on a later turn
 * becomes a failure in its original hour, and one cleared by a {@code false_alarm} resolve stops being one. The
 * reset fence keeps a resolve durable; a change to the scorer or the tuning is handled as a reset too, with the
 * note {@value #TUNING_CHANGED}, because a reference learned under other weights is not comparable.
 *
 * <p>Only a rise is reported. Runs when the TURN sweep reaches the head of the stream, never mid-page, so the
 * assessment table is complete up to the cursor whenever this reads it. It reads tables, not the provider, so a
 * paused classifier still replays.
 */
@Service
public class FrustrationRateService implements ClassifierCatchUp {

    private static final Logger log = LoggerFactory.getLogger(FrustrationRateService.class);

    /** How far back a replay reads, from the newest scored turn: tool_error's window. */
    static final Duration REPLAY_WINDOW = Duration.ofDays(28);

    /** The reset note written when the scorer or the tuning changed under a call site's state. */
    static final String TUNING_CHANGED = "tuning changed";

    private final FrustrationRateRepository rates;
    private final ObjectMapper mapper;

    public FrustrationRateService(FrustrationRateRepository rates, ObjectMapper mapper) {
        this.rates = rates;
        this.mapper = mapper;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(BuiltInDetector.Kind.FRUSTRATION);
    }

    @Override
    public void caughtUp(ClassifierJobRow job, ClassifierRow signal, @Nullable String checkedBefore) {
        refresh(job.projectId(), signal, Instant.now());
    }

    /**
     * Replay every call site's tallies over the window, save each call site's state, and return the call sites
     * whose frustrated-conversation rate has risen and is alarming now.
     */
    List<Spell> refresh(String projectId, ClassifierRow signal, Instant at) {
        Instant started = Instant.now();
        FrustrationConfig config = FrustrationConfig.of(mapper, signal.configJson());
        String scorerVersion = config.scorerVersion();
        Optional<Instant> newest = rates.newestTurnAt(projectId, signal.id(), scorerVersion);
        if (newest.isEmpty()) return List.of();

        List<HourlyToolTally> all = rates.hourlyTallies(
                projectId, signal.id(), scorerVersion, newest.get().minus(REPLAY_WINDOW));
        List<HourlyToolTally> tallies = all.stream()
                .filter(t -> !FrustrationRateRepository.UNASSIGNED.equals(t.toolKey()))
                .toList();

        String now = at.toString();
        String epoch = config.stateEpoch();
        ToolErrorStateRepository states = rates.states();
        Map<String, CarriedState> carried = new HashMap<>();
        int retuned = 0;
        for (CarriedState state : states.list(projectId)) {
            if (!epoch.equals(state.stateEpoch())) {
                carried.put(state.toolKey(), retune(projectId, state, epoch, now));
                retuned++;
            } else {
                carried.put(state.toolKey(), state.rebuilding());
            }
        }

        ToolErrorTrend.Sweep sweep =
                ToolErrorTrend.sweep(tallies, config.engine(), Map.of(), carried, config.schemaVersion());
        for (CarriedState advanced : sweep.advanced()) states.save(projectId, advanced, now);

        List<Spell> rising = sweep.spells().stream()
                .filter(s -> s.decision().direction() == Direction.UP)
                .toList();
        for (Spell spell : rising) logSpell(projectId, signal, spell);

        StructuredLog.info(log, Markers.OPS, "frustration.refresh")
                .message(
                        "replayed %d hourly bucket(s) across %d call site(s) for frustration, %d in a spell",
                        all.size(), sweep.advanced().size(), rising.size())
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("buckets", all.size())
                .field("callSites", sweep.advanced().size())
                .field("spells", rising.size())
                .field("retuned", retuned)
                .durationMs(started)
                .log();
        return rising;
    }

    /**
     * A state row built under another scorer or other tuning: reset it and re-learn, recorded with its note, and
     * stamp the new epoch so the next pass does not reset it again while it is still learning.
     */
    private CarriedState retune(String projectId, CarriedState stale, String epoch, String now) {
        ToolErrorStateRepository states = rates.states();
        states.resetAndRelearn(projectId, stale.toolKey(), null, TUNING_CHANGED, now);
        CarriedState fresh = new CarriedState(
                stale.toolKey(),
                ToolErrorDetector.State.EMPTY,
                null,
                null,
                epoch,
                stale.pendingPinBy(),
                stale.pendingPinAt(),
                now);
        states.save(projectId, fresh, now);
        return fresh;
    }

    private void logSpell(String projectId, ClassifierRow signal, Spell spell) {
        ToolErrorDetector.Decision d = spell.decision();
        String onset = d.onsetAt();
        StructuredLog.info(log, Markers.OPS, "frustration.spell")
                .message(
                        "%s: %.1f%% of conversations frustrated since %s, against a learned %.1f%%",
                        spell.toolKey(),
                        d.currentRate() * 100,
                        onset == null ? "the window's start" : onset,
                        d.baselineRate() * 100)
                .field("project", projectId)
                .field("classifierId", signal.id())
                .field("callSiteId", spell.toolKey())
                .field("conversationsSinceOnset", d.callsSinceOnset())
                .field("frustratedSinceOnset", d.failuresSinceOnset())
                .field("criticality", d.criticality())
                .log();
    }
}
