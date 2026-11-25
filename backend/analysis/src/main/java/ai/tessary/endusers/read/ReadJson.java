// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Reads the jsonb columns the judge writes into the snake_case records it wrote them from. */
@Component
class ReadJson {

    private final ObjectMapper mapper;

    ReadJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    <T> @Nullable T readNullable(@Nullable String value, TypeReference<T> type) {
        return value == null ? null : read(value, type);
    }
}
