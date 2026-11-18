// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PromptLinkIdsTest {

    private static final Instant T0 = Instant.parse("2026-11-18T09:00:00Z");

    /** Prompts by project, then name; every call is recorded as project plus the sorted names asked for. */
    private static final class FakeLookup implements PromptLinkIds.Lookup {
        final Map<String, Map<String, String>> prompts = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        FakeLookup with(String projectId, String name, String id) {
            prompts.computeIfAbsent(projectId, k -> new HashMap<>()).put(name, id);
            return this;
        }

        @Override
        public Map<String, String> idsByName(String projectId, Set<String> names) {
            calls.add(projectId + " " + new TreeSet<>(names));
            Map<String, String> found = new HashMap<>();
            for (String name : names) {
                String id = prompts.getOrDefault(projectId, Map.of()).get(name);
                if (id != null) found.put(name, id);
            }
            return found;
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aBatchOfManyNamesCostsOneLookup() {
        // Bug: the link is read once per span, so a batch of linked spans adds a read per span to the ingest path.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-a").with("p1", "b", "id-b");
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock());

        Map<String, String> resolved = ids.resolve("p1", Set.of("a", "b", "c"));

        assertEquals(Map.of("a", "id-a", "b", "id-b"), resolved);
        assertEquals(List.of("p1 [a, b, c]"), lookup.calls);
    }

    @Test
    void aResolvedNameIsNotReadAgainWithinTheTtl() {
        // Bug: every batch reads the prompt table again for names it resolved a moment ago.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-a");
        MutableClock clock = new MutableClock();
        PromptLinkIds ids = new PromptLinkIds(lookup, clock);
        ids.resolve("p1", Set.of("a"));
        clock.now = T0.plus(PromptLinkIds.TTL).minusSeconds(1);

        Map<String, String> resolved = ids.resolve("p1", Set.of("a"));

        assertEquals(Map.of("a", "id-a"), resolved);
        assertEquals(List.of("p1 [a]"), lookup.calls);
    }

    @Test
    void aResolvedNameIsReadAgainAtTheTtl() {
        // Bug: a prompt deleted and created again under the same name keeps the old id for as long as the process runs.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-a");
        MutableClock clock = new MutableClock();
        PromptLinkIds ids = new PromptLinkIds(lookup, clock);
        ids.resolve("p1", Set.of("a"));
        lookup.with("p1", "a", "id-a2");
        clock.now = T0.plus(PromptLinkIds.TTL);

        Map<String, String> resolved = ids.resolve("p1", Set.of("a"));

        assertEquals(Map.of("a", "id-a2"), resolved);
        assertEquals(List.of("p1 [a]", "p1 [a]"), lookup.calls);
    }

    @Test
    void anUnknownNameIsAskedAgainSoAPromptCreatedLaterLinks() {
        // Bug: a miss is remembered, so spans of a prompt created after its first linked span stay unlinked.
        FakeLookup lookup = new FakeLookup();
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock());
        ids.resolve("p1", Set.of("a"));
        lookup.with("p1", "a", "id-a");

        Map<String, String> resolved = ids.resolve("p1", Set.of("a"));

        assertEquals(Map.of("a", "id-a"), resolved);
    }

    @Test
    void onlyTheNamesNotCachedAreAskedFor() {
        // Bug: one new name in a batch reads every name of the batch again.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-a").with("p1", "b", "id-b");
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock());
        ids.resolve("p1", Set.of("a"));

        Map<String, String> resolved = ids.resolve("p1", Set.of("a", "b"));

        assertEquals(Map.of("a", "id-a", "b", "id-b"), resolved);
        assertEquals(List.of("p1 [a]", "p1 [b]"), lookup.calls);
    }

    @Test
    void aBatchWithNoPromptNamesReadsNothing() {
        // Bug: a batch of unlinked spans, the common case, still runs a read.
        FakeLookup lookup = new FakeLookup();
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock());

        assertEquals(Map.of(), ids.resolve("p1", Set.of()));
        assertEquals(List.of(), lookup.calls);
    }

    @Test
    void theSameNameInTwoProjectsDoesNotCollide() {
        // Bug: the cache is keyed on the name alone, so a span of one project is linked to another project's prompt.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-1").with("p2", "a", "id-2");
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock());
        ids.resolve("p1", Set.of("a"));

        assertEquals(Map.of("a", "id-2"), ids.resolve("p2", Set.of("a")));
        assertEquals(Map.of(), ids.resolve("p3", Set.of("a")));
    }

    @Test
    void theOldestEntryIsEvictedAtTheBound() {
        // Bug: the cache grows with every name ever seen, so a producer that sends invented names exhausts the heap.
        FakeLookup lookup = new FakeLookup().with("p1", "a", "id-a").with("p1", "b", "id-b");
        PromptLinkIds ids = new PromptLinkIds(lookup, new MutableClock(), 1);
        ids.resolve("p1", Set.of("a"));
        ids.resolve("p1", Set.of("b"));

        ids.resolve("p1", Set.of("a"));

        assertEquals(List.of("p1 [a]", "p1 [b]", "p1 [a]"), lookup.calls);
    }
}
