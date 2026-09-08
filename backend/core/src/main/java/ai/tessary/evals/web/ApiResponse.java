// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(ResponseMeta meta, @Nullable T data) {

    public static <T> ApiResponse<T> ok(@Nullable T data) {
        return new ApiResponse<>(ResponseMeta.ok(), data);
    }

    public static ApiResponse<Void> failure(int status, ErrorBody error) {
        return new ApiResponse<>(ResponseMeta.failure(status, error), null);
    }
}
