// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Proves the deployment contract between the compose/SSM env vars and {@link ObserverProperties}:
 * {@code EVALS_OBSERVER_ENCODER_URL} / {@code EVALS_OBSERVER_ENCODER_API_KEY} must reach
 * {@code evals.observer.encoder.*} through Spring's relaxed env-var binding. There is no
 * fallback endpoint, so a binding regression here would silently break every signal sweep
 * in production — this pins the exact variable names the infrastructure delivers.
 */
class ObserverPropertiesBindingTest {

    @Test
    void encoderEnvVarsBindToEncoderProperties() {
        StandardEnvironment env = new StandardEnvironment();
        // The name must end in "systemEnvironment" — Spring applies relaxed env-var name
        // mangling only to system-environment property sources, which is the exact
        // mechanism the production container relies on.
        env.getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "test-systemEnvironment",
                        Map.of(
                                "EVALS_OBSERVER_ENCODER_URL", "http://classify.test.internal:8080",
                                "EVALS_OBSERVER_ENCODER_API_KEY", "test-bearer")));

        ObserverProperties props = Binder.get(env)
                .bind("evals.observer", Bindable.ofInstance(new ObserverProperties()))
                .get();

        assertThat(props.getEncoder().getUrl()).isEqualTo("http://classify.test.internal:8080");
        assertThat(props.getEncoder().getApiKey()).isEqualTo("test-bearer");
    }

    @Test
    void encoderDefaultsAreBlankNotNull() {
        ObserverProperties props = new ObserverProperties();
        assertThat(props.getEncoder().getUrl()).isEmpty();
        assertThat(props.getEncoder().getApiKey()).isEmpty();
    }
}
