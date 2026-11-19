// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import ai.tessary.featureflags.DbFeatureFlags;
import ai.tessary.featureflags.OrgFeatureFlagRepository;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.tenant.OrganizationCreatedEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Writes explicit on-overrides for {@link #ON_FOR_NEW_ORGS} when an org is created, in every build, so the build's
 * default (off) keeps holding only the orgs that existed before. An override rather than a default, because a default
 * would also turn the capabilities on for every existing org.
 */
@Component
public class NewOrgCapabilities {

    private static final Logger log = LoggerFactory.getLogger(NewOrgCapabilities.class);

    static final List<Capability> ON_FOR_NEW_ORGS = List.of(Capability.RCA_AUTOMATIC, Capability.FIX_PR);

    private final OrgFeatureFlagRepository overrides;

    /** Optional for the same reason as on {@code CapabilityController}. */
    private final ObjectProvider<DbFeatureFlags> openFlags;

    public NewOrgCapabilities(OrgFeatureFlagRepository overrides, ObjectProvider<DbFeatureFlags> openFlags) {
        this.overrides = overrides;
        this.openFlags = openFlags;
    }

    /** The sign-in paths have already written the org, so a failure here is logged and the org keeps the default. */
    @EventListener
    public void onOrganizationCreated(OrganizationCreatedEvent event) {
        try {
            for (Capability capability : ON_FOR_NEW_ORGS) {
                overrides.insertIfAbsent(event.orgId(), capability.wire(), true);
            }
        } catch (DataAccessException e) {
            StructuredLog.warn(log, Markers.OPS, "capability.new-org-overrides-failed")
                    .message("could not write the new-org capability overrides for " + event.orgId())
                    .field("org", event.orgId())
                    .field("error", e.getMessage())
                    .log();
            return;
        }
        openFlags.ifAvailable(flags -> flags.invalidate(event.orgId()));
    }
}
