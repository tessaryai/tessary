// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * One INFO line at startup naming the resolved edition. It is the positive, log-readable assertion a boot
 * check needs: an image that claims to be paid but boots the open classpath prints {@code edition=open},
 * and a health endpoint reporting 200 cannot tell those two apart.
 */
@Component
public class EditionBanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EditionBanner.class);

    private final Edition edition;

    public EditionBanner(Edition edition) {
        this.edition = edition;
    }

    @Override
    public void afterSingletonsInstantiated() {
        log.info("edition={}", edition.wire());
    }
}
