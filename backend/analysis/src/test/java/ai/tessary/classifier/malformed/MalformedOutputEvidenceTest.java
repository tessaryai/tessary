// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The message a failing output's row shows for the field a reader selected, read back from the
 * detection's own evidence blob.
 */
class MalformedOutputEvidenceTest {

    /**
     * Catches the message of a different field's violation being shown (the first violation instead of the
     * matching one), and an old-shaped or unreadable blob failing the whole failing-outputs page instead of
     * showing the row with no message.
     */
    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(
            nullValues = "NULL",
            delimiter = '|',
            value = {
                "NULL | answer | NULL",
                "'   ' | answer | NULL",
                "'{\"violations\":[' | answer | NULL",
                "'{\"reason\":\"not_json\"}' | not_json | NULL",
                "'{\"violations\":[\"$.answer: is missing\"]}' | answer | NULL",
                "'{\"violations\":[{\"field\":\"items[].sku\",\"message\":\"sku must be a string\"},"
                        + "{\"field\":\"answer\",\"message\":\"answer is required\"}]}' | answer | answer is required",
                "'{\"violations\":[{\"field\":\"items[].sku\",\"message\":\"sku must be a string\"}]}' | qty | NULL"
            })
    void readsTheMatchingViolationsMessageOrNone(String evidence, String field, String expected) {
        assertEquals(expected, MalformedOutputEvidence.messageForField(evidence, field));
    }
}
