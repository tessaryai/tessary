// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How this instance runs the groundedness model, bound from {@code tessary.groundedness.*}. Where the
 * model is and its key are {@code tessary.observer.encoder.*} ({@link ObserverProperties}); the
 * classifier's operating point is per-project data on its {@code config_json}.
 *
 * <p>{@code classifierMode} is {@code TESSARY_GROUNDEDNESS_CLASSIFIER_MODE}, which the setup writes
 * into {@code .env}. {@link Mode#DEV} is a model a developer runs on their own machine: sweeps run
 * whenever it answers, and it being down means scoring is paused. {@link Mode#PRODUCTION} is a GPU
 * instance that wakes on a schedule and stops itself when idle: after a sweep catches up, groundedness
 * is not enqueued again for {@code productionSleepMinutes}, so the instance can go idle and stop, and
 * the model being down between runs is normal. Unset or blank is dev; anything else but the two
 * values fails startup rather than guessing.
 */
@Component
@ConfigurationProperties(prefix = "tessary.groundedness")
public class GroundednessProperties {

    /** The two ways the groundedness model runs. */
    public enum Mode {
        DEV,
        PRODUCTION;

        /** The wire and {@code .env} spelling. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final String ENV = "TESSARY_GROUNDEDNESS_CLASSIFIER_MODE";

    private Mode classifierMode = Mode.DEV;

    /** In production mode, how long after a caught-up sweep groundedness is not enqueued again. */
    private long productionSleepMinutes = 30;

    /** In production mode, how long without a caught-up sweep before the row reports no scores. */
    private long productionMissedRunMinutes = 120;

    /** The parsed mode. Named apart from the bound property so the binder sees one type, a string. */
    public Mode mode() {
        return classifierMode;
    }

    public String getClassifierMode() {
        return classifierMode.wire();
    }

    /**
     * Case-insensitive; blank is dev, because an unset mode with a model URL set is a developer's
     * machine. An unknown value throws, which fails the bind and so startup, naming the variable.
     */
    public void setClassifierMode(@Nullable String value) {
        this.classifierMode = parse(value);
    }

    static Mode parse(@Nullable String value) {
        if (value == null || value.isBlank()) return Mode.DEV;
        for (Mode m : Mode.values()) {
            if (m.wire().equals(value.trim().toLowerCase(Locale.ROOT))) return m;
        }
        throw new IllegalArgumentException(
                ENV + " must be dev or production, got '" + value.trim() + "'");
    }

    public long getProductionSleepMinutes() {
        return productionSleepMinutes;
    }

    public void setProductionSleepMinutes(long productionSleepMinutes) {
        this.productionSleepMinutes = productionSleepMinutes;
    }

    public long getProductionMissedRunMinutes() {
        return productionMissedRunMinutes;
    }

    public void setProductionMissedRunMinutes(long productionMissedRunMinutes) {
        this.productionMissedRunMinutes = productionMissedRunMinutes;
    }
}
