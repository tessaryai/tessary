// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptService.CreateVia;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The server's fetch cache with a counting loader in place of the database. */
class PromptFetchCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-18T09:00:00Z");
    private static final PromptSelector PRODUCTION = new PromptSelector.Label("production");

    private static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Counts its calls and answers the version it is set to. */
    private static final class Loader implements Supplier<ResolvedVersion> {
        int calls;
        ResolvedVersion answer;

        Loader(ResolvedVersion answer) {
            this.answer = answer;
        }

        @Override
        public ResolvedVersion get() {
            calls++;
            return answer;
        }
    }

    private final MutableClock clock = new MutableClock();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static ResolvedVersion version(String projectId, int n, String body) {
        PromptRow prompt = new PromptRow("id-" + projectId, projectId, "p", PromptType.TEXT, List.of(), n, "u", T0, T0);
        PromptVersionRow row = new PromptVersionRow(
                "v" + n, projectId, prompt.id(), n, "\"" + body + "\"", "{}", null, "u", CreateVia.API, T0);
        return new ResolvedVersion(
                prompt, row, new PromptBody.Text(body), Map.of(), List.of("production", "latest"), true);
    }

    private PromptFetchCache cache() {
        return cache(new PromptCacheProperties());
    }

    private PromptFetchCache cache(PromptCacheProperties props) {
        return new PromptFetchCache(props, clock);
    }

    @Test
    void secondFetchWithinTtlDoesNotLoad() {
        // Bug: every fetch still goes to the database, so an SDK fleet starting cold holds the connection pool.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);
        clock.now = T0.plusSeconds(59);

        ResolvedVersion second = cache.fetch("p1", "p", PRODUCTION, load);

        assertSame(load.answer, second);
        assertEquals(1, load.calls);
    }

    @Test
    void writeDropsTheProjectsEntries() {
        // Bug: after production moves, the server keeps serving the old version for the full TTL.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);
        cache.invalidate("p1");
        load.answer = version("p1", 2, "two");

        assertEquals(2, cache.fetch("p1", "p", PRODUCTION, load).version().version());
    }

    @Test
    void loadThatStartedBeforeAWriteStoresNothing() {
        // Bug: a slow read stores the old version after the write invalidated it.
        PromptFetchCache cache = cache();
        ResolvedVersion old = version("p1", 1, "one");
        cache.fetch("p1", "p", PRODUCTION, () -> {
            cache.invalidate("p1");
            return old;
        });
        Loader load = new Loader(version("p1", 2, "two"));

        assertEquals(2, cache.fetch("p1", "p", PRODUCTION, load).version().version());
        assertEquals(1, load.calls);
    }

    @Test
    void notFoundIsNotCached() {
        // Bug: a prompt created after a 404 stays "not found" for the TTL.
        PromptFetchCache cache = cache();
        assertThrows(
                TessaryException.class,
                () -> cache.fetch("p1", "p", PRODUCTION, () -> {
                    throw new TessaryException(PromptsError.NOT_FOUND, "p");
                }));
        Loader load = new Loader(version("p1", 1, "one"));

        assertEquals(1, cache.fetch("p1", "p", PRODUCTION, load).version().version());
        assertEquals(1, load.calls);
    }

    @Test
    void otherProjectsEntriesSurviveAWrite() {
        // Bug: a write in one project empties every project's cache, so one busy editor sends all fetches to the DB.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p2", 1, "one"));
        cache.fetch("p2", "p", PRODUCTION, load);
        cache.invalidate("p1");

        cache.fetch("p2", "p", PRODUCTION, load);

        assertEquals(1, load.calls);
    }

    @Test
    void sameNameInTwoProjectsDoesNotCollide() {
        // Bug: the key leaves out the project, so one tenant receives another tenant's prompt body.
        PromptFetchCache cache = cache();
        cache.fetch("p1", "p", PRODUCTION, () -> version("p1", 1, "mine"));

        ResolvedVersion other = cache.fetch("p2", "p", PRODUCTION, () -> version("p2", 7, "theirs"));

        assertEquals("p2", other.prompt().projectId());
        assertEquals(7, other.version().version());
    }

    @Test
    void selectorsAreSeparateEntries() {
        // Bug: the key leaves out the selector, so a fetch of version 1 answers with the production version.
        PromptFetchCache cache = cache();
        cache.fetch("p1", "p", PRODUCTION, () -> version("p1", 2, "two"));

        ResolvedVersion v1 = cache.fetch("p1", "p", new PromptSelector.Version(1), () -> version("p1", 1, "one"));

        assertEquals(1, v1.version().version());
    }

    @Test
    void entryExpiresAtTtl() {
        // Bug: an entry never expires, so a change made on another node never shows here.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);
        clock.now = T0.plusSeconds(60);

        cache.fetch("p1", "p", PRODUCTION, load);

        assertEquals(2, load.calls);
    }

    @Test
    void ttlZeroBypasses() {
        // Bug: the documented off switch still caches.
        PromptCacheProperties props = new PromptCacheProperties();
        props.setTtlSeconds(0);
        PromptFetchCache cache = cache(props);
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);

        cache.fetch("p1", "p", PRODUCTION, load);

        assertEquals(2, load.calls);
    }

    @Test
    void disabledBypasses() {
        // Bug: the kill switch is ignored.
        PromptCacheProperties props = new PromptCacheProperties();
        props.setEnabled(false);
        PromptFetchCache cache = cache(props);
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);

        cache.fetch("p1", "p", PRODUCTION, load);

        assertEquals(2, load.calls);
    }

    @Test
    void oldestEntryIsEvictedAtTheEntryBound() {
        // Bug: the cache grows with every prompt fetched and exhausts the heap.
        PromptCacheProperties props = new PromptCacheProperties();
        props.setMaxEntries(1);
        PromptFetchCache cache = cache(props);
        Loader a = new Loader(version("p1", 1, "a"));
        cache.fetch("p1", "a", PRODUCTION, a);
        cache.fetch("p1", "b", PRODUCTION, () -> version("p1", 1, "b"));

        cache.fetch("p1", "a", PRODUCTION, a);

        assertEquals(2, a.calls);
    }

    @Test
    void oldestEntryIsEvictedAtTheCharacterBound() {
        // Bug: a few huge bodies fill the heap while staying under the entry bound.
        PromptCacheProperties props = new PromptCacheProperties();
        props.setMaxBodyChars(20);
        PromptFetchCache cache = cache(props);
        Loader a = new Loader(version("p1", 1, "a".repeat(10)));
        Loader b = new Loader(version("p1", 1, "b".repeat(10)));
        cache.fetch("p1", "a", PRODUCTION, a);
        cache.fetch("p1", "b", PRODUCTION, b);

        cache.fetch("p1", "b", PRODUCTION, b);
        cache.fetch("p1", "a", PRODUCTION, a);

        assertEquals(1, b.calls, "the newer entry stays");
        assertEquals(
                2, a.calls, "each entry is 12 body and 2 config characters, so two pass 20 and the older one goes");
    }

    @Test
    void invalidationInsideATransactionWaitsForTheCommit() {
        // Bug: a write drops entries before it commits, and a reader on another connection caches the old row again
        // under the new generation, where nothing drops it until the TTL ends.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidate("p1");
        cache.fetch("p1", "p", PRODUCTION, load);
        assertEquals(1, load.calls, "before the commit the entry is still served");

        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(TransactionSynchronization::afterCommit);
        cache.fetch("p1", "p", PRODUCTION, load);
        assertEquals(2, load.calls, "after the commit the entry is gone");
    }

    @Test
    void rolledBackWriteInvalidatesNothing() {
        // Bug: a write that failed and rolled back still empties the project's cache.
        PromptFetchCache cache = cache();
        Loader load = new Loader(version("p1", 1, "one"));
        cache.fetch("p1", "p", PRODUCTION, load);
        TransactionSynchronizationManager.initSynchronization();

        cache.invalidate("p1");
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        cache.fetch("p1", "p", PRODUCTION, load);

        assertEquals(1, load.calls);
    }
}
