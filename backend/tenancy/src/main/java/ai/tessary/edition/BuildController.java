// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import ai.tessary.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The running build, for any signed-in caller. Deployment-wide, so it sits outside an org path. */
@RestController
public class BuildController {

    private final Edition edition;

    public BuildController(Edition edition) {
        this.edition = edition;
    }

    public record BuildView(String version) {}

    @GetMapping("/api/build")
    public ApiResponse<BuildView> build() {
        return ApiResponse.ok(new BuildView(edition.version()));
    }
}
