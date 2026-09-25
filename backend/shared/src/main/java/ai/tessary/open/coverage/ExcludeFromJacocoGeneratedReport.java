// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.coverage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Leaves a method, constructor or class out of the JaCoCo coverage report. JaCoCo drops any element
 * carrying an annotation whose simple name contains {@code Generated} (class retention or wider),
 * which is the only reason the name says so.
 *
 * <p>Only for code no test can reach: a catch the compiler requires but the platform cannot throw,
 * or code whose only job is a call to an external service (GitHub, E2B, WorkOS, an LLM provider,
 * the GPU model). Keep the annotated element as small as possible, and say which case it is in
 * {@link #value()}.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface ExcludeFromJacocoGeneratedReport {

    /** Why no test reaches this code. */
    String value();
}
