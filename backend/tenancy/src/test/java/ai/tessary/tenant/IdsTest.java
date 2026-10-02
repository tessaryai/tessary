// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Slugify drives URL paths and DB unique-constraint keys; quirks here surface
 * as 404s or violation errors that look like bugs in unrelated controllers.
 * ULID drives every primary key; if the time-prefix isn't monotonic we lose
 * the natural creation-order sort that several queries depend on.
 */
class IdsTest {

    @ParameterizedTest(name = "slugify(\"{0}\") = {1}: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            Acme Corp        | acme-corp        | lowercases and dashes
            hello world      | hello-world      | lowercases and dashes
            a!!!b@@@c        | a-b-c            | collapses non-alphanumeric runs
            hello___world    | hello-world      | collapses non-alphanumeric runs
            !!!Acme!!!       | acme             | strips leading and trailing punctuation
            ---acme---       | acme             | strips leading and trailing punctuation
            ''               | project          | falls back to project when empty
            !!!              | project          | falls back to project when empty
            v1 pipeline 2025 | v1-pipeline-2025 | accepts digits
            """)
    void slugify(String input, String slug, String rule) {
        assertEquals(slug, Ids.slugify(input), rule);
    }

    @Test
    void slugify_truncatesAndRemovesTrailingDashAfterTruncation() {
        // 60-char-then-dash-then-more: should not leave the trailing dash from truncation
        String input = "a".repeat(60) + " more after the cut";
        String s = Ids.slugify(input);
        assertTrue(s.length() <= 60, "slug must be <= 60 chars");
        assertFalse(s.endsWith("-"), "truncated slug must not end with a dash");
    }

    @Test
    void ulid_correctLengthAndAlphabet() {
        String id = Ids.ulid();
        assertEquals(26, id.length());
        for (char c : id.toCharArray()) {
            assertTrue(
                    "0123456789ABCDEFGHJKMNPQRSTVWXYZ".indexOf(c) >= 0,
                    "ULID char '" + c + "' must be in Crockford alphabet");
        }
    }

    @Test
    void ulid_uniqueInRapidSuccession() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) seen.add(Ids.ulid());
        assertEquals(1000, seen.size(), "1000 IDs must all be distinct");
    }

    /**
     * The ULID spec's own example: 1469922850259 ms (2016-07-30T23:54:10.259Z) is the 48-bit big-endian
     * time prefix {@code 01ARZ3NDEK} in Crockford base32. The largest 48-bit time, 2^48 - 1, is
     * {@code 7ZZZZZZZZZ}: the first character carries only the top three bits.
     */
    @Test
    void ulid_timePrefixIsTheSpecEncodingOfItsInstant() {
        assertEquals(
                "01ARZ3NDEK", Ids.ulid(Instant.ofEpochMilli(1_469_922_850_259L)).substring(0, 10));
        assertEquals(
                "7ZZZZZZZZZ", Ids.ulid(Instant.ofEpochMilli((1L << 48) - 1)).substring(0, 10));
    }

    @Test
    void ulid_timePrefixIsSortableByCreationOrder() {
        String a = Ids.ulid(Instant.ofEpochMilli(1_469_922_850_259L));
        String b = Ids.ulid(Instant.ofEpochMilli(1_469_922_850_260L));
        // One millisecond later is the next Crockford digit in the last prefix place (K, then M).
        assertEquals("01ARZ3NDEM", b.substring(0, 10));
        assertTrue(a.compareTo(b) < 0, "earlier ULID must lex-sort before later one");
    }
}
