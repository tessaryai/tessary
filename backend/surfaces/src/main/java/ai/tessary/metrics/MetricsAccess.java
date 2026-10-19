// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.auth.TenantPathResolver.Resolved;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.rbac.Permission;

/**
 * The access gate both metrics controllers share. A key context carries role member, which holds ORG_MANAGE, so
 * {@link #write} refuses a key before it checks the permission.
 */
public final class MetricsAccess {

    private MetricsAccess() {}

    /** Resolve, refuse a write-only key, require ORG_VIEW. */
    public static Resolved read(TenantPathResolver resolver, TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        if (ctx.isMcpToken() && !ctx.keyPermits(KeyScope.QUERY))
            throw new TessaryException(MetricsError.WRONG_KEY_SCOPE);
        r.require(Permission.ORG_VIEW, "read metrics");
        return r;
    }

    /** Resolve, refuse every key, require ORG_MANAGE. */
    public static Resolved write(TenantPathResolver resolver, TenantContext ctx, String orgSlug, String projectSlug) {
        Resolved r = resolver.requireProject(ctx, orgSlug, projectSlug);
        if (ctx.isMcpToken()) throw new TessaryException(MetricsError.SESSION_REQUIRED);
        r.require(Permission.ORG_MANAGE, "edit dashboards");
        return r;
    }

    public static boolean canManage(Resolved r, TenantContext ctx) {
        return r.can(Permission.ORG_MANAGE) && !ctx.isMcpToken();
    }
}
