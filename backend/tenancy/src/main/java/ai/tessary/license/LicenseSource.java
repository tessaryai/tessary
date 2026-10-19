// SPDX-License-Identifier: Apache-2.0
package ai.tessary.license;

import java.util.Optional;

/**
 * Where the deployment's license comes from. This build has none; another build verifies a signed one and supplies
 * this bean. Empty means "no license": nothing is capped, as on an open install.
 */
public interface LicenseSource {

    /** The license in force now, or empty when there is none or it has lapsed. */
    Optional<License> current();

    static LicenseSource none() {
        return Optional::empty;
    }
}
