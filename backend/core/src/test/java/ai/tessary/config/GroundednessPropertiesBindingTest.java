// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.tessary.config.GroundednessProperties.Mode;
import java.util.Map;
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
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "production")).mode())
                .isEqualTo(Mode.PRODUCTION);
        assertThat(bind(Map.of("TESSARY_GROUNDEDNESS_CLASSIFIER_MODE", "PRODUCTION")).mode())
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

    @Test
    void theTimingDefaultsKeepIdleStopUnderTheSleepUnderTheWake() {
        GroundednessProperties props = new GroundednessProperties();
        // The GPU instance stops after 10 idle minutes and wakes hourly; the sleep sits between.
        assertThat(props.getProductionSleepMinutes()).isEqualTo(30);
        assertThat(props.getProductionMissedRunMinutes()).isEqualTo(120);
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
