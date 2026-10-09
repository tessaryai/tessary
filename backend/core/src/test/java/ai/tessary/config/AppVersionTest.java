// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link AppVersion#gitRef}: a release version links its tag, anything else links {@code main}. */
class AppVersionTest {

    @Test
    void aReleaseVersionIsItsTag() {
        assertThat(AppVersion.gitRef("1.3.0")).isEqualTo("v1.3.0");
        assertThat(AppVersion.gitRef("12.0.10")).isEqualTo("v12.0.10");
    }

    @Test
    void devIsMain() {
        assertThat(AppVersion.gitRef("dev")).isEqualTo("main");
    }
}
