// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller (or one handler on it) as reachable only by an org holding {@code value}.
 * {@link CapabilityInterceptor} enforces it before the handler runs; the response is the same
 * {@code 403 CAPABILITY_DISABLED} an explicit {@link CapabilityService#require} produces.
 *
 * <p><b>Why an annotation and not a call per method.</b> The capability half of the product is roughly
 * twenty controllers and well over a hundred handlers. Gating those by hand means the gate is only as good
 * as the least careful method, and the failure is silent: a new endpoint on a gated controller is simply
 * open, and nothing anywhere says it should not be. Declaring it once per class makes the gate a property
 * of the surface rather than of each method on it.
 *
 * <p>Hiding a surface in the SPA is not gating it. Segment F removed the navigation for every capability an
 * org does not hold, which is what a partner sees; this is what a partner's script sees. Both are needed and
 * they are different mechanisms — the same finding segment H made about the Providers tab, generalized.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresCapability {

    /** The capability the org must hold. */
    Capability value();
}
