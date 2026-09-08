// The eval's bridge into the SHIPPING tool-error detector. Driven by tool_error/bridge.py.
//
// A jshell snippet, not a compilation unit: never compiled by Maven, never linted by spotless, never
// covered by a test. It exists so PROGRAM.md §12's runs measure `ToolErrorDetector` and `ToolErrorTrend`
// THEMSELVES rather than a Python restatement of their arithmetic — the whole argument for the null run
// setting `decision_interval` collapses if the thing being tuned is not the thing that ships.
//
// Its OWN bridge rather than an extension of metric_drift's, deliberately: a different statistic, a
// different config record, and that file belongs to a segment being edited in parallel.
//
// Because nothing compiles this, it drifts from the backend silently and only a run finds out.
// metric_drift's copy drifted twice — once on a package move, once on a record arity — so if an entry
// point here fails on an unresolved import or an argument count, that is what happened: fix the snippet
// against the current signatures rather than working around it.

import ai.tessary.evals.classifier.toolerror.ToolErrorConfig;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.Decision;
import ai.tessary.evals.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.evals.classifier.toolerror.ToolErrorRate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;

var MAPPER = new ObjectMapper();

/**
 * EIGHT components, called positionally so this breaks loudly the next time one is added or removed.
 * That is the intended failure mode: a bridge that silently accepted a changed record would be a bridge
 * measuring a detector the backend no longer has.
 */
ToolErrorConfig configOf(double h, double minEffect, int minBaseline) {
    return new ToolErrorConfig(
            h,
            ToolErrorConfig.DEFAULT_SHIFT_MULTIPLE,
            ToolErrorConfig.DEFAULT_SHIFT_FLOOR,
            minEffect,
            minBaseline,
            ToolErrorConfig.DEFAULT_DOWN_ARM_MIN_RATE,
            ToolErrorConfig.DEFAULT_SETTLE_SECONDS,
            ToolErrorConfig.DEFAULT_MAX_PATTERNS);
}

/** A reference window of raw counts, built the way the replay builds one. */
ToolErrorRate rateOf(long calls, long failures) {
    ToolErrorRate r = new ToolErrorRate();
    r.addCounts(calls, failures);
    return r;
}

/**
 * Replay one tool's buckets and report WHERE it first alarmed, in calls.
 *
 * <p>Reports the first crossing rather than the end state because that is what a run length is: the
 * distance to the alarm, not whether one is standing at the end of the corpus.
 */
ObjectNode replay(JsonNode series, double h, double minEffect, int minBaseline) {
    ToolErrorConfig config = configOf(h, minEffect, minBaseline);

    // Reference first, exactly as ToolErrorTrend does: leading buckets until thick enough, then frozen.
    ToolErrorRate baseline = new ToolErrorRate();
    int i = 0;
    JsonNode buckets = series.path("buckets");
    while (i < buckets.size() && baseline.calls() < config.minBaselineCalls()) {
        baseline.addCounts(buckets.get(i).path("calls").asLong(), buckets.get(i).path("failures").asLong());
        i++;
    }

    ObjectNode out = MAPPER.createObjectNode();
    out.put("tool", series.path("tool").asText());
    if (baseline.calls() < config.minBaselineCalls()) {
        out.put("armed", false);
        out.putNull("alarm_after_calls");
        return out;
    }
    out.put("armed", true);
    out.put("baseline_calls", baseline.calls());
    out.put("baseline_rate", baseline.rate());

    State state = State.EMPTY;
    long seen = 0;
    for (int j = i; j < buckets.size(); j++) {
        JsonNode b = buckets.get(j);
        long calls = b.path("calls").asLong();
        long failures = b.path("failures").asLong();
        state = ToolErrorDetector.advanceBucket(state, baseline, config, calls, failures, b.path("bucket").asText());
        seen += calls;
        Decision d = ToolErrorDetector.decide(state, baseline, config);
        if (d.fired()) {
            out.put("alarm_after_calls", seen);
            out.put("alarm_at", b.path("bucket").asText());
            out.put("statistic", d.statistic());
            out.put("effect_size", d.effectSize());
            out.put("cur_rate", d.currentRate());
            out.put("onset_at", d.onsetAt() == null ? "" : d.onsetAt());
            return out;
        }
    }
    out.putNull("alarm_after_calls");
    out.put("calls_seen", seen);
    out.put("final_statistic", Math.max(state.sUp(), state.sDown()));
    return out;
}

String run(String inPath, String outPath) throws Exception {
    JsonNode request = MAPPER.readTree(Files.readString(Path.of(inPath)));
    double h = request.path("h").asDouble(6.0);
    double minEffect = request.path("min_effect_size").asDouble(0.05);
    int minBaseline = request.path("min_baseline_calls").asInt(500);

    ArrayNode results = MAPPER.createArrayNode();
    for (JsonNode series : request.path("series")) {
        results.add(replay(series, h, minEffect, minBaseline));
    }
    ObjectNode response = MAPPER.createObjectNode();
    response.set("results", results);
    Files.writeString(Path.of(outPath), MAPPER.writeValueAsString(response));
    return "TOOL-ERROR-BRIDGE OK " + results.size();
}

System.out.println(run(System.getProperty("te.in"), System.getProperty("te.out")));
/exit
