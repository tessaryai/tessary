// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * The one JSON codec of the prompt library, for jsonb columns and the body and config size caps. Its inputs are values
 * Jackson itself parsed or rows this library wrote, so neither checked exception can fire.
 */
final class PromptJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private PromptJson() {}

    @ExcludeFromJacocoGeneratedReport(
            "serializes strings, lists and maps Jackson parsed, so the checked catch cannot fire")
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    @ExcludeFromJacocoGeneratedReport("reads jsonb this library wrote, so the checked catch cannot fire")
    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    static String readString(String json) {
        return read(json, new TypeReference<String>() {});
    }

    static List<Object> readList(String json) {
        return read(json, LIST);
    }

    static Map<String, Object> readObject(String json) {
        return read(json, OBJECT);
    }

    static List<String> readStrings(String json) {
        return read(json, STRINGS);
    }
}
