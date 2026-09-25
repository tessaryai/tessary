// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.malformed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which lines of the document a reader is shown light up for a failing field. The line numbers are read
 * off this hand-written text, one-based:
 *
 * <pre>
 *  1 {
 *  2   "answer" : 42,
 *  3   "items" : [ {
 *  4     "sku" : 1
 *  5   }, {
 *  6     "sku" : 2
 *  7   } ],
 *  8   "meta" : {
 *  9     "source" : "x"
 * 10   }
 * 11 }
 * </pre>
 */
class MalformedOutputHighlightTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DOCUMENT = """
            {
              "answer" : 42,
              "items" : [ {
                "sku" : 1
              }, {
                "sku" : 2
              } ],
              "meta" : {
                "source" : "x"
              }
            }""";

    /**
     * Catches a highlight that marks only the first array element's {@code sku} when every element broke it,
     * that marks one line of an object that spans several, or that marks nothing for a {@code required}
     * violation (the property is missing, so the object that should hold it is what to point at).
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "answer        | 2",
                "items[].sku   | 4 6",
                "items         | 3 4 5 6 7",
                "meta          | 8 9 10",
                "meta.author   | 8",
                "items[].price | 3 5",
                "extra         | 1",
                "ghost.x       | ''"
            })
    void highlightsEveryLineTheFieldOccupiesOrItsParentOpensOn(String field, String lines) {
        assertEquals(parse(lines), MalformedOutputHighlight.forField(JSON, DOCUMENT, field));
    }

    /**
     * Catches a root-level array keying its elements with a leading dot ({@code .sku} or {@code [].}),
     * a spelling the detector never writes; and a document that is empty or does not parse throwing out of
     * the page instead of rendering without a highlight.
     */
    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "'[ { \"sku\" : 1 }, { \"sku\" : 2 } ]' | [].sku | 1",
                "''                        | answer | ''",
                "'{ not json'              | answer | ''"
            })
    void aRootArrayCollapsesItsIndexAndAnUnreadableDocumentHighlightsNothing(
            String document, String field, String lines) {
        assertEquals(parse(lines), MalformedOutputHighlight.forField(JSON, document, field));
    }

    private static List<Integer> parse(String lines) {
        if (lines.isBlank()) return List.of();
        return Arrays.stream(lines.trim().split(" ")).map(Integer::valueOf).toList();
    }
}
