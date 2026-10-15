// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.coverage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Excludes an element from JaCoCo's report; the name contains {@code Generated} only because that is what JaCoCo keys
 * on. Only for code no test can reach (a required catch the platform cannot throw, or a bare call to an external
 * service), kept as small as possible.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface ExcludeFromJacocoGeneratedReport {

    String value();
}
