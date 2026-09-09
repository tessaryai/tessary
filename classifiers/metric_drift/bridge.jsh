// The eval's bridge into the SHIPPING detector. Driven by metric_drift/bridge.py; read the module
// README before changing anything here.
//
// This is a jshell snippet file, not a compilation unit of the backend: it is never compiled by
// Maven, never linted by spotless, and adds nothing to the classifier slice's build. It exists so
// that PLAN.md §9's three runs measure `MetricDriftDetector`, `MetricHistogram` and
// `MetricSuppression` THEMSELVES rather than a Python restatement of their arithmetic. The whole
// argument for the eval — that the null case can set `w1_floor` — collapses if the thing being
// tuned is not the thing that ships. behaviour drift learned that the expensive way: its Java port
// capped novelty at order 2 and emitted one detection per trace while the Python side kept firing
// every longest-order novel gram, and every offline number measured before anyone noticed described
// a detector that does not exist (see classifiers/tests/test_behavior_drift_parity.py).
//
// Protocol: one JSON request in, one JSON response out, both via files named by system properties
// (`-R-Dmd.in=` / `-R-Dmd.out=`). Batched rather than per-decision because a JVM start is ~2s and a
// null run makes thousands of comparisons. The final line on stdout is the sentinel bridge.py greps
// for; if a snippet throws, jshell prints the trace and that line never appears, which is how a
// failure here becomes a Python exception instead of a silently empty report.

import ai.tessary.classifier.metric.MetricDriftConfig;
import ai.tessary.classifier.metric.MetricDriftDetector;
import ai.tessary.classifier.metric.MetricDriftDetector.Decision;
import ai.tessary.classifier.metric.MetricDriftDetector.Reference;
import ai.tessary.classifier.metric.MetricFindingEvidence;
import ai.tessary.classifier.metric.MetricHistogram;
import ai.tessary.classifier.metric.MetricHistogram.Grid;
import ai.tessary.classifier.metric.MetricSuppression;
import ai.tessary.classifier.metric.MetricSuppression.Explanation;
import ai.tessary.classifier.metric.MetricSuppression.Shift;
import ai.tessary.classifier.substrate.ActionSymbol;
import ai.tessary.vitals.TokenPriceBook;
import ai.tessary.vitals.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

var MAPPER = new ObjectMapper();

/**
 * A config carrying the caller's operating point. Only `minSample` and `w1Floor` reach `decide`;
 * the rest are the record's own defaults, and `windowTargetCount` is held at or above `minSample`
 * because the compact constructor clamps the two against each other (a min_sample above the target
 * count is a broken setting, not a stricter one).
 */
MetricDriftConfig configOf(String measure, int minSample, double w1Floor, double explainedBy, int bins) {
    return new MetricDriftConfig(
            List.of(measure),
            Math.max(MetricDriftConfig.DEFAULT_WINDOW_TARGET_COUNT, minSample),
            MetricDriftConfig.DEFAULT_WINDOW_MAX_HOURS,
            minSample,
            w1Floor,
            explainedBy,
            MetricDriftConfig.DEFAULT_SETTLE_SECONDS,
            bins);
}

/** The measure's own grid, re-laid on the requested bin count exactly as MetricDriftConfig does. */
Grid gridOf(String name, int bins) {
    Grid base = "cost".equals(name) ? Grid.cost() : Grid.duration();
    return bins == base.bins() ? base : new Grid(base.lo(), base.ratio(), bins);
}

/**
 * Fill a real MetricHistogram from raw measure values. The log is taken HERE, once, because that is
 * where the sweep takes it — `MetricSketch.add` is documented as log space and a caller handing it
 * milliseconds produces a number that is neither a duration nor a ratio.
 */
MetricHistogram sketchOf(JsonNode spec) {
    MetricHistogram hist = new MetricHistogram(gridOf(spec.path("grid").asText("duration"), spec.path("bins").asInt(320)));
    for (JsonNode v : spec.path("values")) {
        hist.add(Math.log(v.asDouble()));
    }
    return hist;
}

double orNaN(OptionalDouble value) {
    return value.isPresent() ? value.getAsDouble() : Double.NaN;
}

void putNumber(ObjectNode node, String field, double value) {
    if (Double.isFinite(value)) node.put(field, value);
    else node.putNull(field);
}

Set<String> stringSet(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode v : array) out.add(v.asText());
    return out;
}

String orNull(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
}

/**
 * The price book, built once per request and only if something needs it — reading the vendored
 * LiteLLM snapshot off the backend's own target/classes, so the eval prices a corpus at exactly the
 * rates the sweep would. A Python price table would be a second book to keep in step, and the failure
 * of the two disagreeing is silent: an unpriced model reads as a cost improvement.
 */
TokenPriceBook PRICES = null;

TokenPriceBook prices() {
    if (PRICES == null) PRICES = new TokenPriceBook(MAPPER);
    return PRICES;
}

/**
 * One turn's dollars and token buckets, summed over its llm leaves — `MetricSource.spendOf`'s
 * arithmetic, reached through the same two classes rather than restated.
 *
 * <p>`TokenUsage.plus` normalizes both sides before adding, so the running total is disjoint at every
 * step: an OpenAI generation arrives wearing Anthropic key names while still carrying a
 * cache-INCLUSIVE input count, and adding the raw blobs bills its cache reads twice.
 *
 * <p><b>One unpriced leaf abstains the whole turn</b> — a null `cost_usd`, never a zero. Pricing the
 * rest would understate that turn's spend by an unknown amount and put a plausible number into the
 * distribution, which is worse than the honest gap; an unpriced model is unpriced, not free.
 *
 * <p>The four bucket sums are reported as sums, NOT as the abstention-aware `tok_*` measures — the
 * harness reads only the cache-read ratio the injector collapses, and `tok_cache_write`'s "reported
 * zero versus not measured at all" distinction is a property of a measure this eval does not run.
 */
ObjectNode priceTurn(JsonNode spec) {
    TokenUsage total = TokenUsage.EMPTY;
    BigDecimal usd = BigDecimal.ZERO;
    boolean anyReported = false;
    boolean unpriced = false;
    for (JsonNode leaf : spec.path("leaves")) {
        JsonNode usage = leaf.get("usage");
        if (usage == null || !usage.isObject()) continue;
        anyReported = true;
        String model = orNull(leaf, "model");
        TokenUsage parsed = TokenUsage.of(usage, model);
        total = total.plus(parsed);
        var leafCost = prices().costOf(model, parsed);
        if (leafCost.isEmpty()) unpriced = true;
        else usd = usd.add(leafCost.get());
    }

    TokenUsage disjoint = total.nonOverlapping();
    ObjectNode out = MAPPER.createObjectNode();
    out.put("id", spec.path("id").asText());
    if (!anyReported || unpriced) out.putNull("cost_usd");
    else out.put("cost_usd", usd.doubleValue());
    if (anyReported) {
        out.put("input_tokens", disjoint.inputTokens());
        out.put("output_tokens", disjoint.outputTokens());
        out.put("cache_read_tokens", disjoint.cacheReadTokens());
        out.put("cache_write_tokens", disjoint.cacheWriteTokens());
    } else {
        out.putNull("input_tokens");
        out.putNull("output_tokens");
        out.putNull("cache_read_tokens");
        out.putNull("cache_write_tokens");
    }
    return out;
}

String run(String inPath, String outPath) throws Exception {
    JsonNode request = MAPPER.readTree(Files.readString(Path.of(inPath)));

    // Sketches are built once and referenced by id: a window is compared against both its previous
    // and its pinned reference, and a floor sweep re-decides the same pair at a dozen candidate
    // floors, so binning the samples per decision would dominate the run.
    Map<String, MetricHistogram> sketches = new LinkedHashMap<>();
    ArrayNode edges = MAPPER.createArrayNode();
    for (JsonNode spec : request.path("sketches")) {
        MetricHistogram hist = sketchOf(spec);
        sketches.put(spec.path("id").asText(), hist);
        // Edge counters are reported for every sketch, not only the interesting ones: PLAN.md §11
        // names "overflow bin non-empty in the null run" as the signal that a grid's range is wrong,
        // and a range that is wrong is invisible in the decisions themselves.
        ObjectNode edge = edges.addObject();
        edge.put("id", spec.path("id").asText());
        edge.put("count", hist.count());
        edge.put("underflow", hist.underflow());
        edge.put("overflow", hist.overflow());
    }

    Map<String, Decision> decisions = new LinkedHashMap<>();
    Map<String, MetricHistogram> jobRef = new LinkedHashMap<>();
    Map<String, MetricHistogram> jobCur = new LinkedHashMap<>();
    ArrayNode jobsOut = MAPPER.createArrayNode();
    for (JsonNode job : request.path("jobs")) {
        String id = job.path("id").asText();
        String measure = job.path("measure").asText();
        MetricHistogram ref = job.path("ref").isNull() ? null : sketches.get(job.path("ref").asText());
        MetricHistogram cur = sketches.get(job.path("cur").asText());
        MetricDriftConfig config = configOf(
                measure,
                job.path("min_sample").asInt(150),
                job.path("w1_floor").asDouble(0.18),
                job.path("explained_by_fraction").asDouble(0.5),
                job.path("bins").asInt(320));
        Reference reference = "pinned".equals(job.path("reference").asText()) ? Reference.PINNED : Reference.PREVIOUS;

        Decision decision = MetricDriftDetector.decide(measure, reference, ref, cur, config);
        decisions.put(id, decision);
        if (ref != null) jobRef.put(id, ref);
        jobCur.put(id, cur);

        String bucketKey = job.path("bucket_key").asText("");
        ObjectNode out = jobsOut.addObject();
        out.put("id", id);
        out.put("fired", decision.fired());
        out.put("measure", measure);
        out.put("reference", decision.reference().wire());
        out.put("w1_log", decision.w1Log());
        out.put("ratio", decision.ratio());
        out.put("direction", decision.direction().wire());
        out.put("n_ref", decision.nRef());
        out.put("n_cur", decision.nCur());
        if (decision.silence() == null) out.putNull("silence");
        else out.put("silence", decision.silence().name());
        // The user-visible strings, built by the same code the finding and the case are built from,
        // so an eval report and a Triage row cannot disagree about what fired.
        out.put("cause_key", MetricFindingEvidence.causeKey(measure, bucketKey, decision));
        out.put("title", MetricFindingEvidence.title(measure, bucketKey, decision));
        putNumber(out, "ref_p50", ref == null ? Double.NaN : orNaN(MetricFindingEvidence.rawQuantile(ref, 0.5)));
        putNumber(out, "cur_p50", orNaN(MetricFindingEvidence.rawQuantile(cur, 0.5)));
        putNumber(out, "ref_p95", ref == null ? Double.NaN : orNaN(MetricFindingEvidence.rawQuantile(ref, 0.95)));
        putNumber(out, "cur_p95", orNaN(MetricFindingEvidence.rawQuantile(cur, 0.95)));
    }

    // §6.1's cross-grain rule, run over decisions this same request produced. Which turn/tool closes
    // are offered to it together is the caller's judgement (the sweep pairs whatever rotated in one
    // page); what "explains" means is decided here, by MetricSuppression itself.
    ArrayNode suppressionOut = MAPPER.createArrayNode();
    for (JsonNode entry : request.path("suppression")) {
        String turnJob = entry.path("turn_job").asText();
        Decision turnDecision = decisions.get(turnJob);
        ObjectNode out = suppressionOut.addObject();
        out.put("id", entry.path("id").asText());
        if (turnDecision == null || !turnDecision.fired()) {
            out.putNull("suppressed_by");
            out.putNull("covered");
            continue;
        }
        Shift turn = new Shift(
                turnDecision.measure(),
                entry.path("turn_bucket_key").asText(""),
                stringSet(entry.path("turn_call_sites")),
                turnDecision,
                orNaN(MetricFindingEvidence.rawQuantile(jobRef.get(turnJob), 0.5)),
                orNaN(MetricFindingEvidence.rawQuantile(jobCur.get(turnJob), 0.5)));

        List<Shift> tools = new ArrayList<>();
        List<String> toolJobIds = new ArrayList<>();
        for (JsonNode tool : entry.path("tools")) {
            String toolJob = tool.path("job").asText();
            Decision toolDecision = decisions.get(toolJob);
            // Only a shift can explain a shift: a comparison that stayed under the floor did not move.
            if (toolDecision == null || !toolDecision.fired()) continue;
            tools.add(new Shift(
                    toolDecision.measure(),
                    tool.path("bucket_key").asText(""),
                    stringSet(tool.path("call_sites")),
                    toolDecision,
                    orNaN(MetricFindingEvidence.rawQuantile(jobRef.get(toolJob), 0.5)),
                    orNaN(MetricFindingEvidence.rawQuantile(jobCur.get(toolJob), 0.5))));
            toolJobIds.add(toolJob);
        }

        Explanation best = MetricSuppression.explain(turn, tools, entry.path("explained_by_fraction").asDouble(0.5));
        if (best == null) {
            out.putNull("suppressed_by");
            out.putNull("covered");
        } else {
            out.put("suppressed_by", toolJobIds.get(tools.indexOf(best.tool())));
            out.put("suppressed_by_bucket", best.tool().bucketKey());
            out.put("covered", best.covered());
        }
    }

    // Corpus resolution, not detection: what the EXPORT could not answer in SQL. Both sections are
    // normally empty (they run once, at load time, and never during a floor sweep), and both exist so
    // that the population the eval measures over is the population the sweep would build — a
    // false-positive rate counted over differently-bucketed traffic is a fact about the export.
    ArrayNode symbolsOut = MAPPER.createArrayNode();
    for (JsonNode spec : request.path("symbols")) {
        ObjectNode out = symbolsOut.addObject();
        out.put("id", spec.path("id").asText());
        // isError = false throughout, as MetricSource.toolMetrics mints it: a tool's failures stay in
        // the same latency population as its successes, or half the samples land in a bucket with no
        // baseline and a tool that starts failing fast reads as traffic migrating rather than as a
        // shift in the one distribution.
        out.put("bucket_key", ActionSymbol.of(orNull(spec, "kind"), orNull(spec, "name"), false));
    }

    ArrayNode pricedOut = MAPPER.createArrayNode();
    for (JsonNode spec : request.path("priced")) {
        pricedOut.add(priceTurn(spec));
    }

    ObjectNode response = MAPPER.createObjectNode();
    response.set("jobs", jobsOut);
    response.set("sketch_edges", edges);
    response.set("suppression", suppressionOut);
    response.set("symbols", symbolsOut);
    response.set("priced", pricedOut);
    Files.writeString(Path.of(outPath), MAPPER.writeValueAsString(response));
    return "METRIC-DRIFT-BRIDGE OK " + jobsOut.size();
}

System.out.println(run(System.getProperty("md.in"), System.getProperty("md.out")));
/exit
