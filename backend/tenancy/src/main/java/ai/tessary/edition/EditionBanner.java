// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * One INFO line at startup naming the resolved edition, so what actually booted is checkable
 * alongside a health endpoint that only reports 200.
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
