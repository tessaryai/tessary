// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a controller or handler to orgs holding {@code value}, enforced by {@link CapabilityInterceptor} with the
 * same {@code 403 CAPABILITY_DISABLED} as {@link CapabilityService#require}. Per class so a new endpoint on a gated
 * controller cannot be silently open. Hiding a surface in the SPA is not gating it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresCapability {

    Capability value();
}
