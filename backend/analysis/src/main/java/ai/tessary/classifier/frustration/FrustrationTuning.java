// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import ai.tessary.classifier.ClassifierDtos.FrustrationCallSiteView;
import ai.tessary.classifier.ClassifierDtos.FrustrationTuningView;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.toolerror.CarriedState;
import ai.tessary.classifier.toolerror.ToolErrorConfig;
import ai.tessary.classifier.toolerror.ToolErrorDetector;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.classifier.toolerror.ToolErrorDetector.Direction;
import ai.tessary.classifier.toolerror.ToolErrorRate;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The Frustration Tuning view: the classifier's dials, and per call site the learned rate, {@code h(p0)}, the
 * accumulator and the last human reset, so a reader can see what each call site learned from what and why a
 * quiet one is quiet ({@code learning n/200}).
 */
@Service
public class FrustrationTuning {

    private final FrustrationRateRepository rates;
    private final ObjectMapper mapper;

    public FrustrationTuning(FrustrationRateRepository rates, ObjectMapper mapper) {
        this.rates = rates;
        this.mapper = mapper;
    }

    public FrustrationTuningView view(String projectId, ClassifierRow signal) {
        if (!BuiltInDetector.Kind.FRUSTRATION.equals(signal.detector())) {
            throw new TessaryException(ClassifierError.NOT_FRUSTRATION, signal.classifierKey());
        }
        FrustrationConfig config = FrustrationConfig.of(mapper, signal.configJson());
        ToolErrorConfig engine = config.engine();
        String scorerVersion = config.scorerVersion();
        List<HourlyToolTally> tallies = rates.newestTurnAt(projectId, signal.id(), scorerVersion)
                .map(newest -> rates.hourlyTallies(
                        projectId, signal.id(), scorerVersion, newest.minus(FrustrationRateService.REPLAY_WINDOW)))
                .orElse(List.of());

        long unassigned = 0;
        Map<String, List<HourlyToolTally>> byCallSite = new TreeMap<>();
        for (HourlyToolTally t : tallies) {
            if (FrustrationRateRepository.UNASSIGNED.equals(t.toolKey())) {
                unassigned += t.calls();
            } else {
                byCallSite.computeIfAbsent(t.toolKey(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<String, CarriedState> states = rates.states().byTool(projectId);
        Map<String, FrustrationRateRepository.Reset> resets = rates.resets(projectId);

        Set<String> callSites = new TreeSet<>(byCallSite.keySet());
        callSites.addAll(states.keySet());
        List<FrustrationCallSiteView> views = new ArrayList<>();
        for (String callSite : callSites) {
            views.add(callSite(
                    callSite,
                    states.get(callSite),
                    resets.get(callSite),
                    byCallSite.getOrDefault(callSite, List.of()),
                    engine));
        }
        return new FrustrationTuningView(
                config.threshold(),
                config.arlTarget(),
                config.minDecisionInterval(),
                config.shiftMultiple(),
                config.shiftFloor(),
                config.minBaselineConversations(),
                scorerVersion,
                unassigned,
                List.copyOf(views));
    }

    private static FrustrationCallSiteView callSite(
            String callSite,
            @Nullable CarriedState state,
            FrustrationRateRepository.@Nullable Reset reset,
            List<HourlyToolTally> tallies,
            ToolErrorConfig engine) {
        @Nullable String resetAt = reset == null ? null : reset.resetAt();
        @Nullable String resetNote = reset == null ? null : reset.note();
        @Nullable ToolErrorRate baseline = state == null ? null : state.baseline();
        if (state == null || baseline == null) {
            long learned = 0;
            for (HourlyToolTally t : tallies) {
                if (state == null || !state.fencedOff(t.bucket())) learned += t.calls();
            }
            return new FrustrationCallSiteView(
                    callSite,
                    FrustrationCallSiteView.LEARNING,
                    learned,
                    null,
                    null,
                    null,
                    null,
                    state == null ? 0.0 : state.state().sUp(),
                    null,
                    resetAt,
                    resetNote);
        }
        double p0 = ToolErrorDetector.baselineRate(baseline);
        Decision decision = ToolErrorDetector.decide(state.state(), baseline, engine);
        boolean alarming = decision.fired() && decision.direction() == Direction.UP;
        return new FrustrationCallSiteView(
                callSite,
                alarming ? FrustrationCallSiteView.ALARMING : FrustrationCallSiteView.IN_CONTROL,
                baseline.calls(),
                baseline.calls(),
                baseline.failures(),
                p0,
                engine.decisionIntervalFor(p0),
                state.state().sUp(),
                state.state().onsetUpAt(),
                resetAt,
                resetNote);
    }
}
