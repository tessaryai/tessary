// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.malformed.MalformedOutputSchema.Field;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The schema tree "How outputs broke" renders. A row's {@code path} is the key a failure count is filed
 * under, so it must spell a field exactly as {@code MalformedOutputDetector} collapses a violation's
 * location: an array's items nest under {@code items[]}, and a count filed there must land on that row.
 */
class MalformedOutputSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SCHEMA = """
            {"type":"object","required":["answer","items"],"properties":{
              "answer":{"type":"string"},
              "items":{"type":"array","items":{"type":"object","required":["sku"],"properties":{
                 "sku":{"type":"string"},
                 "qty":{"type":["integer","null"]}}}},
              "meta":{"properties":{"source":{"enum":["a","b"]}}},
              "tags":{"items":{"type":"string"}},
              "flags":{"type":"array"},
              "note":{}
            }}
            """;

    /**
     * Catches a tree whose array children are filed under the wrong key ({@code items.sku} instead of
     * {@code items[].sku}), which leaves every array-field failure count on no row, and a type column
     * that reads blank for a field declared by enum, by properties, by items, or by a type union.
     */
    @Test
    void flattensEveryNamedFieldInSchemaOrderWithItsCollapsedPathTypeAndCount() throws Exception {
        List<Field> fields =
                MalformedOutputSchema.flatten(JSON.readTree(SCHEMA), Map.of("answer", 3L, "items[].sku", 2L));

        assertEquals(
                List.of(
                        new Field("answer", "answer", "string", true, 0, 3),
                        new Field("items", "items[]", "array", true, 0, 0),
                        new Field("items[].sku", "sku", "string", true, 1, 2),
                        new Field("items[].qty", "qty", "integer|null", false, 1, 0),
                        new Field("meta", "meta", "object", false, 0, 0),
                        new Field("meta.source", "source", "enum", false, 1, 0),
                        new Field("tags", "tags[]", "array", false, 0, 0),
                        new Field("flags", "flags[]", "array", false, 0, 0),
                        new Field("note", "note", "", false, 0, 0)),
                fields);
    }
}
