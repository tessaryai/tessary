// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Slugify drives URL paths and DB unique-constraint keys; quirks here surface
 * as 404s or violation errors that look like bugs in unrelated controllers.
 * ULID drives every primary key; if the time-prefix isn't monotonic we lose
 * the natural creation-order sort that several queries depend on.
 */
class IdsTest {

    @Test
    void slugify_basicLowercaseDashes() {
        assertEquals("acme-corp", Ids.slugify("Acme Corp"));
        assertEquals("hello-world", Ids.slugify("hello world"));
    }

    @Test
    void slugify_collapsesNonAlphanumericRuns() {
        assertEquals("a-b-c", Ids.slugify("a!!!b@@@c"));
        assertEquals("hello-world", Ids.slugify("hello___world"));
    }

    @Test
    void slugify_stripsTrailingAndLeadingPunctuation() {
        assertEquals("acme", Ids.slugify("!!!Acme!!!"));
        assertEquals("acme", Ids.slugify("---acme---"));
    }

    @Test
    void slugify_fallsBackToProjectWhenEmpty() {
        assertEquals("project", Ids.slugify(""));
        assertEquals("project", Ids.slugify("!!!"));
        assertEquals("project", Ids.slugify(null));
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
    void slugify_acceptsDigits() {
        assertEquals("v1-pipeline-2025", Ids.slugify("v1 pipeline 2025"));
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

    @Test
    void ulid_timePrefixIsSortableByCreationOrder() throws InterruptedException {
        String a = Ids.ulid();
        Thread.sleep(5);
        String b = Ids.ulid();
        // Prefix is the time portion (10 chars). Compare lexicographically.
        assertNotEquals(a.substring(0, 10), b.substring(0, 10), "5ms gap must produce distinct time prefixes");
        assertTrue(a.substring(0, 10).compareTo(b.substring(0, 10)) < 0, "earlier ULID must lex-sort before later one");
    }
}
