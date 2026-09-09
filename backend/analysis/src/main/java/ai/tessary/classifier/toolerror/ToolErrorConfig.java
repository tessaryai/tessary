// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * The {@code tool_error} classifier's operating point. Design contract:
 * {@code classifiers/tool_error/PROGRAM.md} §4 and §9.
 *
 * <p>Shaped after {@code CusumParams} and {@code MetricDriftConfig} — per-project dials, clamped on
 * construction, every default tagged {@code EXPERIMENT(tool-error-tuning)} — and deliberately its own
 * record rather than a reuse of either. It shares no dial that means the same thing as metric drift's:
 * a floor in log units on a heavy-tailed distribution and a decision interval in log-likelihood units
 * on a proportion are different quantities, and one record would invite tuning either by looking at the
 * other.
 *
 * <p><b>The arithmetic these numbers feed, and the evidence behind them, is
 * {@code docs/concepts/deviation-math.md}.</b> Changing a default here without updating that document
 * leaves the only human-readable account of the operating point describing a detector that no longer
 * exists — the same-PR co-update rule in the root {@code AGENTS.md} covers it.
 *
 * @param arlTarget calls a healthy tool should run between false alarms. <b>The only threshold dial.</b>
 *     The alarm threshold {@code h} is derived from it per tool by {@link #decisionIntervalFor(double)}
 * @param shiftMultiple the multiple of the in-control rate the detector is tuned to catch quickly
 * @param shiftFloor the smallest absolute rise worth arming for, as a rate — what makes a tool that
 *     has never failed detectable at all
 * @param minEffectSize Cohen's h reported on a finding. <b>Not a trigger</b> — see the constant
 * @param minBaselineCalls calls the in-control reference must hold before anything is judged
 * @param downArmMinRate in-control rate below which the improvement arm does not run
 * @param settleSeconds RETIRED as a read filter, kept on the wire. The repository now gates on
 *     {@code trace.is_settled}, which states what this window was estimating; the field stays because it
 *     is persisted classifier config and dropping it from the schema would fail to parse every deployed
 *     bundle that carries it.
 * @param maxPatterns failure signatures a finding's breakdown carries before the tail folds
 */
public record ToolErrorConfig(
        long arlTarget,
        double shiftMultiple,
        double shiftFloor,
        double minEffectSize,
        int minBaselineCalls,
        double downArmMinRate,
        int settleSeconds,
        int maxPatterns) {

    /**
     * EXPERIMENT(tool-error-tuning): calls a healthy tool should run between false alarms — the ARL₀.
     *
     * <p><b>This is the only threshold dial, and it is the one that means something to a person.</b>
     * Nobody sets {@code h} any more; it is derived per tool by {@link #decisionIntervalFor(double)} so
     * that every tool, however clean or noisy, cries wolf at about the same rate.
     *
     * <p>The dial used to be {@code h} itself, flat at 6.0, on the stated grounds that {@code h} is
     * "remarkably flat in the base rate". It is not flat enough, and the table that said so only went up
     * to 5%. Solved exactly, a flat 6.0 delivers an ARL₀ of 308,498 at a 0.5% in-control rate but 31,700
     * at 5% and 6,936 at 20% — eight and forty-four times hotter than intended. That leak is what
     * {@link #DEFAULT_MIN_EFFECT_SIZE} was added to plug, and plugging it downstream of the accumulator
     * is what made the detector deaf to real outages.
     */
    public static final long DEFAULT_ARL_TARGET = 250_000L;

    /**
     * EXPERIMENT(tool-error-tuning): the {@code h}-versus-base-rate fit, {@code intercept + slope·ln(p0)}.
     *
     * <p>Fitted to the exact threshold at each base rate, solved by {@code classifiers/tool_error/arl.py}
     * (Brook–Evans on a refined integer lattice). It yields 6.0 at 0.5%, 6.4 at 1%, 8.2 at 5% and 9.7 at
     * 20%, holding the realised ARL₀ between 220k and 309k against the 250k target. A flat threshold
     * spans 6,936 to 308,498 over the same range, so this collapses a 44x spread to 1.4x.
     *
     * <p>Below about 0.5% the fit stops describing anything and {@link #MIN_DECISION_INTERVAL} takes
     * over. That is not a fudge: below there {@link #DEFAULT_SHIFT_FLOOR} rather than the multiple sets
     * {@code p1}, so the relationship changes shape, and the exact thresholds sit at 5.8 to 6.1 across
     * the whole regime. One number is the honest description of a flat stretch.
     *
     * <p><b>Exact arithmetic, not a measured operating point.</b> Every figure assumes independent
     * Bernoulli trials, and real tool failures are bursty — one upstream outage fails two hundred
     * consecutive calls — which inflates the false-alarm rate by an amount nobody here has measured.
     * PROGRAM.md §12's null run against a real corpus is what replaces these two numbers. Expect it to
     * push them up, not down.
     */
    public static final double ARL_FIT_INTERCEPT = 11.42;

    /** EXPERIMENT(tool-error-tuning): see {@link #ARL_FIT_INTERCEPT}. */
    public static final double ARL_FIT_SLOPE = 1.088;

    /**
     * Bounds on the derived threshold. Below 6 the alarm fires on ordinary sampling noise whatever the
     * rate; above 12 the run length exceeds any corpus anyone will replay, so the detector cannot be shown
     * to work at all. Both ends are also outside the range the fit was measured over.
     */
    public static final double MIN_DECISION_INTERVAL = 6.0;

    /** See {@link #MIN_DECISION_INTERVAL}. */
    public static final double MAX_DECISION_INTERVAL = 12.0;

    /**
     * EXPERIMENT(tool-error-tuning): the shift the detector is tuned to catch quickly, as a multiple of
     * the in-control rate. A doubling.
     *
     * <p>This is not a threshold — a smaller shift is still caught, only later, and a larger one sooner.
     * It sets where the detector is most sensitive, which is the question "what size of regression do we
     * want to hear about within a day rather than within a week".
     */
    public static final double DEFAULT_SHIFT_MULTIPLE = 2.0;

    /**
     * EXPERIMENT(tool-error-tuning): the smallest absolute rise worth arming for, as a rate. Half a
     * percentage point.
     *
     * <p><b>Without this the most alarming case in the product is undetectable.</b> A tool that has never
     * failed has an in-control rate at or near zero, and twice nearly-zero is still nearly-zero — so a
     * purely multiplicative target would tune the detector to catch a shift too small to distinguish from
     * silence. The floor says: however clean this tool has been, arm for it starting to fail one call in
     * two hundred.
     */
    public static final double DEFAULT_SHIFT_FLOOR = 0.005;

    /**
     * EXPERIMENT(tool-error-tuning): a reference point for Cohen's h. <b>Never a trigger.</b>
     *
     * <p>It used to gate every alarm, and that was the wrong place for it. A CUSUM accumulates evidence
     * indefinitely, so on a busy tool it eventually crosses on a tenth of a percentage point, and this
     * gate was what suppressed those. But it measured the shift using the failure rate over <em>every
     * call since the reference was pinned</em>, which on a tool with a large pinned history is the
     * baseline by construction. A 5% tool going to 80% read as 5.001%, h=0.00004, and was silenced; so
     * was every sustained shift the detector is actively tuned for.
     *
     * <p>The small-shift leak it was covering for is now handled where it belongs, in
     * {@link #decisionIntervalFor(double)}: a threshold calibrated to the tool's own base rate means the
     * accumulator's break-even rate refuses small drifts on its own, and nothing downstream has to.
     *
     * <p>0.05 remains a useful landmark when reading a finding — a doubling from 1% to 2% is h=0.083 and
     * 20.0% to 21.1% is h=0.032 — which is why the number survives even though nothing branches on it.
     */
    public static final double DEFAULT_MIN_EFFECT_SIZE = 0.05;

    /**
     * EXPERIMENT(tool-error-tuning): calls the in-control reference must hold before anything is judged.
     *
     * <p>A <em>wait</em>, not a skip: a tool under this keeps accumulating rather than being dropped, so
     * a rarely-called tool is watched on a slower clock instead of never (PROGRAM.md §3.3). 500 is about
     * the least from which a rate near 1% can be told from a rate near 2% at all; below it the interval
     * on the in-control estimate is wider than the shifts worth catching, and the CUSUM would be
     * accumulating evidence against a number that is itself noise.
     */
    public static final int DEFAULT_MIN_BASELINE_CALLS = 500;

    /**
     * EXPERIMENT(tool-error-tuning): in-control rate below which the improvement arm does not run.
     *
     * <p>Failures falling is watched because a tool that stopped reporting errors has either been fixed
     * or stopped reporting, and only one of those is good news. But that is only worth saying when there
     * were errors to lose: on a tool already failing one call in a thousand, a halving is both
     * uninteresting and, at any sane run length, undetectable. One percent is where the arm starts
     * earning its keep.
     */
    public static final double DEFAULT_DOWN_ARM_MIN_RATE = 0.01;

    /**
     * EXPERIMENT(tool-error-tuning): five minutes, matching the drift slice, because both wait on the
     * same exporter.
     *
     * <p>This measure genuinely needs it even though a tool call is a single span. The span's arrival is
     * its own completion signal, but the DENOMINATOR is not one span: a turn's tool calls arrive across
     * several batch flushes, so reading a trace early counts some of its calls and not others, and there
     * is no reason to believe the ones that landed first fail at the same rate as the ones that had not.
     */
    public static final int DEFAULT_SETTLE_SECONDS = 300;

    /** EXPERIMENT(tool-error-tuning): signatures in a finding's breakdown before the tail folds. */
    public static final int DEFAULT_MAX_PATTERNS = 8;

    /**
     * Clamped on construction. The blob is operator-editable, and a config that can be edited into
     * nonsense is a detector that can be edited into permanent silence — or into a false alarm every
     * hour — without anybody meaning to.
     */
    public ToolErrorConfig {
        // A budget below a thousand calls is a detector alarming on noise; above a billion it is switched
        // off. Both ends are far outside the range the fit in decisionIntervalFor was measured over.
        arlTarget = clampLong(arlTarget, 1_000L, 1_000_000_000L, DEFAULT_ARL_TARGET);
        // Strictly above 1: a multiple of 1 makes the out-of-control rate equal the in-control one and
        // the log-likelihood ratio identically zero, which is a detector that can never fire.
        shiftMultiple = clamp(shiftMultiple, 1.05, 100.0, DEFAULT_SHIFT_MULTIPLE);
        shiftFloor = clamp(shiftFloor, 0.0001, 0.5, DEFAULT_SHIFT_FLOOR);
        // Up to pi, the largest h two proportions can differ by (0 against 1). Clamped even though nothing
        // branches on it, so a nonsense value cannot reach a finding a human reads.
        minEffectSize = clamp(minEffectSize, 0.0, Math.PI, DEFAULT_MIN_EFFECT_SIZE);
        minBaselineCalls = clampInt(minBaselineCalls, 30, 1_000_000, DEFAULT_MIN_BASELINE_CALLS);
        downArmMinRate = clamp(downArmMinRate, 0.0001, 0.5, DEFAULT_DOWN_ARM_MIN_RATE);
        settleSeconds = clampInt(settleSeconds, 0, 86_400, DEFAULT_SETTLE_SECONDS);
        maxPatterns = clampInt(maxPatterns, 1, ToolErrorRate.MAX_PATTERNS, DEFAULT_MAX_PATTERNS);
    }

    /**
     * The alarm threshold {@code h} for a tool whose in-control rate is {@code p0}, in log-likelihood
     * units. <b>Derived, never configured.</b>
     *
     * <p>Two tools need very different amounts of evidence before a run of failures means anything. On a
     * tool that fails one call in a thousand, four failures in a row is already damning. On one that
     * fails one in five, four failures is a Tuesday, and it takes fifteen. Scoring in log-likelihood
     * units gets most of the way to a common scale but not all of it, and the residual is what a flat
     * threshold got wrong — see {@link #DEFAULT_ARL_TARGET}.
     *
     * <p>The base-rate term is the fit. The budget term needs no fudge factor: solved exactly, ARL₀ rises
     * by 0.99 to 1.02 in the log per unit of {@code h} across the whole 0.5%–20% range, so one e-fold per
     * unit is not an approximation anyone has to apologise for. Moving the budget moves the threshold by
     * its log.
     */
    public double decisionIntervalFor(double p0) {
        if (!Double.isFinite(p0) || p0 <= 0) return MIN_DECISION_INTERVAL;
        double h = ARL_FIT_INTERCEPT + ARL_FIT_SLOPE * Math.log(p0) + Math.log((double) arlTarget / DEFAULT_ARL_TARGET);
        return Math.max(MIN_DECISION_INTERVAL, Math.min(MAX_DECISION_INTERVAL, h));
    }

    /** Every default, for a signal whose blob is absent or unreadable. */
    public static ToolErrorConfig defaults() {
        return new ToolErrorConfig(
                DEFAULT_ARL_TARGET,
                DEFAULT_SHIFT_MULTIPLE,
                DEFAULT_SHIFT_FLOOR,
                DEFAULT_MIN_EFFECT_SIZE,
                DEFAULT_MIN_BASELINE_CALLS,
                DEFAULT_DOWN_ARM_MIN_RATE,
                DEFAULT_SETTLE_SECONDS,
                DEFAULT_MAX_PATTERNS);
    }

    /**
     * Parse a classifier's blob, falling back to the default for any key it does not carry.
     *
     * <p>Unreadable JSON yields the defaults rather than an exception, matching {@code MetricDriftConfig}:
     * a blob written by a newer build must not dead-letter the sweep of an older one, and a sweep that
     * refuses to run is a worse failure than one running at the shipped operating point.
     */
    public static ToolErrorConfig of(ObjectMapper mapper, @Nullable String configJson) {
        if (configJson == null || configJson.isBlank()) return defaults();
        try {
            JsonNode root = mapper.readTree(configJson);
            return new ToolErrorConfig(
                    root.path("arl_target").asLong(DEFAULT_ARL_TARGET),
                    root.path("shift_multiple").asDouble(DEFAULT_SHIFT_MULTIPLE),
                    root.path("shift_floor").asDouble(DEFAULT_SHIFT_FLOOR),
                    root.path("min_effect_size").asDouble(DEFAULT_MIN_EFFECT_SIZE),
                    root.path("min_baseline_calls").asInt(DEFAULT_MIN_BASELINE_CALLS),
                    root.path("down_arm_min_rate").asDouble(DEFAULT_DOWN_ARM_MIN_RATE),
                    root.path("settle_seconds").asInt(DEFAULT_SETTLE_SECONDS),
                    root.path("max_patterns").asInt(DEFAULT_MAX_PATTERNS));
        } catch (JsonProcessingException e) {
            return defaults();
        }
    }

    private static int clampInt(int v, int lo, int hi, int fallback) {
        if (v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    private static long clampLong(long v, long lo, long hi, long fallback) {
        if (v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi, double fallback) {
        if (!Double.isFinite(v) || v <= 0) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }
}
