// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseMeta(
        int status, boolean success, @Nullable ErrorBody error) {
    public static ResponseMeta ok() {
        return new ResponseMeta(200, true, null);
    }

    public static ResponseMeta failure(int status, ErrorBody error) {
        return new ResponseMeta(status, false, error);
    }
}
