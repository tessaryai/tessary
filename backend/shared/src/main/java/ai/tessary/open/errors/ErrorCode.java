// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Marker for hierarchical, structurally-unique error codes.
 *
 * <p>Implementations are always enums; the wire code is derived as
 * {@code <DOMAIN>.<NAME>} where {@code DOMAIN} comes from the enum's
 * declaring class (the {@code Error} suffix is stripped) and {@code NAME}
 * is the enum constant name. The compiler enforces within-domain
 * uniqueness; cross-domain uniqueness is structural (the domain string
 * differs by class). {@link ErrorCatalog} adds a fail-fast startup check
 * for the (rare) case of two enum classes resolving to the same domain.
 */
public interface ErrorCode {

    String name();

    HttpStatus status();

    String template();

    Class<? extends Enum<?>> declaringClass();

    default String domain() {
        String simple = declaringClass().getSimpleName();
        if (simple.endsWith("Errors")) simple = simple.substring(0, simple.length() - 6);
        else if (simple.endsWith("Error")) simple = simple.substring(0, simple.length() - 5);
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
