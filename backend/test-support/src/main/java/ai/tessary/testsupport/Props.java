// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Builds a {@code @ConfigurationProperties} record the way Boot binds it at startup. */
public final class Props {

    private Props() {}

    /**
     * {@code type} with its defaults, overridden by {@code keyValues}: alternating keys, relative to the type's
     * prefix and in yaml spelling ({@code agentic.launcher-url}), and their values. A null value leaves the key
     * unset.
     */
    public static <T> T of(Class<T> type, Object... keyValues) {
        ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
        String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                values.put(prefix + "." + keyValues[i], String.valueOf(keyValues[i + 1]));
            }
        }
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate(prefix, Bindable.of(type));
    }
}
