// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds a {@code @ConfigurationProperties} object from yaml-style keys, the way Boot does at startup. */
final class ConfigBinding {

    private ConfigBinding() {}

    static <T> T bind(String prefix, T target, Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind(prefix, Bindable.ofInstance(target))
                .get();
    }
}
