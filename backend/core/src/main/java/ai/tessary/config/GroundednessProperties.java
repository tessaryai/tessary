// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How this instance runs the groundedness model. {@link Mode#DEV}: sweeps run whenever it answers.
 * {@link Mode#PRODUCTION}: a GPU instance that sleeps; after a caught-up sweep nothing is enqueued for
 * {@code productionSleepMinutes}, so it can stop. Unknown values fail startup.
 */
@Component
@ConfigurationProperties(prefix = "tessary.groundedness")
public class GroundednessProperties {

    public enum Mode {
        DEV,
        PRODUCTION;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final String ENV = "TESSARY_GROUNDEDNESS_CLASSIFIER_MODE";

    private Mode classifierMode = Mode.DEV;

    private long productionSleepMinutes = 30;

    /** How long without a caught-up sweep before the row reports no scores. */
    private long productionMissedRunMinutes = 120;

    /** Named apart from the bound property so the binder sees one type, a string. */
    public Mode mode() {
        return classifierMode;
    }

    public String getClassifierMode() {
        return classifierMode.wire();
    }

    /**
     * Blank is dev, since an unset mode with a model URL is a developer's machine. Unknown throws, failing startup and
     * naming the variable.
     */
    public void setClassifierMode(@Nullable String value) {
        this.classifierMode = parse(value);
    }

    static Mode parse(@Nullable String value) {
        if (value == null || value.isBlank()) return Mode.DEV;
        for (Mode m : Mode.values()) {
            if (m.wire().equals(value.trim().toLowerCase(Locale.ROOT))) return m;
        }
        throw new IllegalArgumentException(ENV + " must be dev or production, got '" + value.trim() + "'");
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
