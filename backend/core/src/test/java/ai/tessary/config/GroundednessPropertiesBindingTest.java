// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.tessary.config.GroundednessProperties.Mode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Pins {@code TESSARY_GROUNDEDNESS_CLASSIFIER_MODE}, the line the groundedness setup writes into
 * {@code .env}, to {@link GroundednessProperties}: both spellings bind, case does not matter, unset or
 * blank is dev, and anything else fails the bind with the variable's name in the message.
 */
class GroundednessPropertiesBindingTest {

    @Test
    void productionBindsFromTheEnvVarWhateverItsCase() {
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "production"))
                        .mode())
                .isEqualTo(Mode.PRODUCTION);
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "PRODUCTION"))
                        .mode())
                .isEqualTo(Mode.PRODUCTION);
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "Dev")).mode())
                .isEqualTo(Mode.DEV);
    }

    @Test
    void unsetOrBlankIsDev() {
        assertThat(bind(Map.of()).mode()).isEqualTo(Mode.DEV);
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "  ")).mode())
                .isEqualTo(Mode.DEV);
    }

    @Test
    void anUnknownModeFailsTheBindNamingTheVariable() {
        assertThatThrownBy(() -> bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "staging")))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE must be dev or production, got 'staging'");
    }

    /**
     * The GPU instance stops itself after {@code IdleMinutes} with no request and is started on a schedule, both
     * set in the AWS template. The sleep must outlast the longest idle stop the template allows, or a re-enqueued
     * sweep keeps the instance up forever; it must end before the next wake, or the woken instance idles out
     * unscored; and a missed run must span more than one wake, or a healthy schedule reads as not scoring.
     */
    @Test
    void theTimingDefaultsKeepIdleStopUnderTheSleepUnderTheWakeUnderAMissedRun() throws IOException {
        String template = Files.readString(Path.of("../../classifiers/groundedness/setup/groundedness-aws.yaml"));
        long idleStopMax = idleMinutesMax(template);
        long wake = wakeMinutes(template);
        GroundednessProperties props = new GroundednessProperties();

        assertThat(idleStopMax).isLessThan(props.getProductionSleepMinutes());
        assertThat(props.getProductionSleepMinutes()).isLessThan(wake);
        assertThat(wake).isLessThan(props.getProductionMissedRunMinutes());
    }

    /** The {@code MaxValue} of the template's {@code IdleMinutes} parameter. */
    private static long idleMinutesMax(String template) {
        Matcher m = Pattern.compile("\\n  IdleMinutes:\\n(?:    .*\\n)*?    MaxValue: (\\d+)\\n")
                .matcher(template);
        assertThat(m.find()).as("IdleMinutes has a MaxValue").isTrue();
        return Long.parseLong(m.group(1));
    }

    /** The template's one {@code rate(N hour[s])} schedule, in minutes. */
    private static long wakeMinutes(String template) {
        Matcher m =
                Pattern.compile("ScheduleExpression: rate\\((\\d+) hours?\\)").matcher(template);
        assertThat(m.find()).as("an hourly ScheduleExpression").isTrue();
        long minutes = Long.parseLong(m.group(1)) * 60;
        assertThat(m.find()).as("one schedule").isFalse();
        return minutes;
    }

    private static GroundednessProperties bind(Map<String, Object> vars) {
        StandardEnvironment env = new StandardEnvironment();
        // Relaxed env-var name mangling applies only to a source whose name ends in
        // "systemEnvironment", which is what a container's environment is.
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", vars));
        return Binder.get(env)
                .bind("tessary.groundedness", Bindable.ofInstance(new GroundednessProperties()))
                .orElseGet(GroundednessProperties::new);
    }
}
