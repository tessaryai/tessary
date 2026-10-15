// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Implemented by enums only. The wire code is {@code <DOMAIN>.<NAME>}, the domain being the declaring class minus its
 * {@code Error} suffix. {@link ErrorCatalog} fails startup if two classes resolve to the same domain.
 */
public interface ErrorCode {

    String name();

    HttpStatus status();

    String template();

    Class<? extends Enum<?>> declaringClass();

    default String domain() {
        String simple = declaringClass().getSimpleName();
        if (simple.endsWith("Error")) simple = simple.substring(0, simple.length() - 5);
        return simple.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT);
    }

    default String code() {
        return domain() + "." + name();
    }

    default String render(@org.jspecify.annotations.Nullable Object... args) {
        if (args == null || args.length == 0) return template();
        try {
            return String.format(Locale.ROOT, template(), args);
        } catch (RuntimeException ignored) {
            return template();
        }
    }
}
