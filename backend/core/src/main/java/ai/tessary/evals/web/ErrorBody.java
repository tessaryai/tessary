// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(
        String code, @Nullable String message, @Nullable Map<String, String> details) {}
