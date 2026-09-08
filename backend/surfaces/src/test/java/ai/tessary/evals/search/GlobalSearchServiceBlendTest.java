// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.tessary.evals.search.GlobalSearchDtos.HitType;
import ai.tessary.evals.search.GlobalSearchDtos.SearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit coverage for the lexical merge in {@link GlobalSearchService}: the reciprocal-rank fusion that,
 * over a single lexical input list, is a stable dedup-and-resort — the no-regression guard for the
 * ⌘K palette's output staying byte-identical now that the semantic leg this used to also blend in
 * (#382/#384) has been removed with the rest of the embedding substrate (#1116). {@link
 * GlobalSearchRepository} is stubbed so this exercises the merge math in isolation — the FTS plumbing is
 * proven by the Testcontainers tests.
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceBlendTest {

    private static final String PID = "proj-1";

    @Mock
    GlobalSearchRepository repo;

    @Test
    void lexicalOrderIsPreserved() {
        // No semantic leg any more: fusion over the single lexical list is the lexical order exactly.
        GlobalSearchService service = new GlobalSearchService(repo);
        SearchHit a = SearchHit.of(HitType.TRACE, "A", "Alpha", null, 9.0);
        SearchHit b = SearchHit.of(HitType.TRACE, "B", "Bravo", null, 2.0);
        when(repo.searchLexical(eq(PID), anyString(), anyInt())).thenReturn(List.of(a, b));

        List<SearchHit> hits = service.search(PID, "q");

        assertEquals(List.of("A", "B"), hits.stream().map(SearchHit::id).toList(), "lexical order is preserved");
    }

    @Test
    void blankQueryNeverFansOut() {
        GlobalSearchService service = new GlobalSearchService(repo);
        assertTrue(service.search(PID, "   ").isEmpty(), "a blank query short-circuits before the lexical leg runs");
        assertFalse(service.search(PID, "").stream().findAny().isPresent(), "an empty query returns no hits");
    }
}
