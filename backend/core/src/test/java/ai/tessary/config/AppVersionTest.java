// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link AppVersion#sourceRef}: a release version links its tag, anything else links {@code main}. */
class AppVersionTest {

    @Test
    void aReleaseVersionIsItsTag() {
        assertThat(AppVersion.sourceRef("1.3.0")).isEqualTo("v1.3.0");
        assertThat(AppVersion.sourceRef("12.0.10")).isEqualTo("v12.0.10");
    }

    @Test
    void devIsMain() {
        assertThat(AppVersion.sourceRef("dev")).isEqualTo("main");
    }

    @Test
    void aShortShaIsMain() {
        assertThat(AppVersion.sourceRef("64f9569")).isEqualTo("main");
        assertThat(AppVersion.sourceRef("0.0.1-SNAPSHOT")).isEqualTo("main");
    }

    @Test
    void outsideAPackagedJarTheVersionIsDev() {
        assertThat(AppVersion.current()).isEqualTo("dev");
    }
}
