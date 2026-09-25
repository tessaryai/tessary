// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import ai.tessary.config.RedactionProperties;
import ai.tessary.ingest.RawEntry;
import ai.tessary.open.errors.RedactionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.redaction.RedactionService.PreviewResult;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The redaction service over an in-memory rule store: the write-path guard's parallel fan-out and every way
 * it can be cut short, the reconciliation of built-in rules onto a project, and the playground's refusals.
 */
class RedactionServiceTest {

    private static final String PID = "proj-1";

    /** Over the sixteen-kilobyte fork threshold, prose only, so no run of it is skipped as binary. */
    private static final String FILLER = "lorem ipsum ".repeat(2000);

    private final FakeRules repo = new FakeRules();
    private final RedactionService svc = new RedactionService(repo, parallel(2));

    @AfterEach
    void closePool() {
        svc.shutdownPool();
    }

    private static RedactionProperties parallel(int n) {
        RedactionProperties p = new RedactionProperties();
        p.setParallelism(n);
        return p;
    }

    /** The rule table, held in memory: the service's contract is what it does with what the store returns. */
    private static final class FakeRules extends RedactionRuleRepository {
        private final List<RedactionRuleRow> rows = new ArrayList<>();

        FakeRules() {
            super(null);
        }

        @Override
        public synchronized List<RedactionRuleRow> findByProject(String projectId) {
            return rows.stream()
                    .filter(r -> r.projectId().equals(projectId))
                    .sorted(Comparator.comparingInt(RedactionRuleRow::sortOrder))
                    .toList();
        }

        @Override
        public synchronized List<RedactionRuleRow> findEnabledByProject(String projectId) {
            return findByProject(projectId).stream()
                    .filter(RedactionRuleRow::enabled)
                    .toList();
        }

        @Override
        public synchronized Optional<RedactionRuleRow> findById(String projectId, String id) {
            return rows.stream()
                    .filter(r -> r.projectId().equals(projectId) && r.id().equals(id))
                    .findFirst();
        }

        @Override
        public synchronized void insert(RedactionRuleRow r) {
            rows.add(r);
        }

        @Override
        public synchronized boolean update(RedactionRuleRow r) {
            boolean found = rows.removeIf(x -> x.id().equals(r.id()));
            rows.add(r);
            return found;
        }

        @Override
        public synchronized boolean setEnabled(String projectId, String id, boolean enabled, String updatedAt) {
            RedactionRuleRow r = findById(projectId, id).orElseThrow();
            return update(new RedactionRuleRow(
                    r.id(),
                    r.projectId(),
                    r.name(),
                    r.pattern(),
                    r.replacement(),
                    enabled,
                    r.builtIn(),
                    r.sortOrder(),
                    r.createdAt(),
                    updatedAt));
        }

        @Override
        public synchronized boolean delete(String projectId, String id) {
            return rows.removeIf(r -> r.projectId().equals(projectId) && r.id().equals(id));
        }

        synchronized RedactionRuleRow named(String name) {
            return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
        }
    }

    /**
     * Metadata that behaves normally on the caller's thread and, on a {@code redaction-} pool worker, first
     * waits for {@code release} and then throws {@code failure} if there is one. The only way to make the
     * fan-out, and only the fan-out, fail or stall without touching the service.
     */
    private static final class PoolSensitiveMetadata extends AbstractMap<String, Object> {
        private final Map<String, Object> values;
        private final CountDownLatch release;
        private final @Nullable Throwable failure;

        PoolSensitiveMetadata(Map<String, Object> values, CountDownLatch release, @Nullable Throwable failure) {
            this.values = values;
            this.release = release;
            this.failure = failure;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            if (Thread.currentThread().getName().startsWith("redaction-")) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("worker never released");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                if (failure instanceof RuntimeException r) throw r;
                if (failure instanceof Error e) throw e;
            }
            return values.entrySet();
        }
    }

    private static RawEntry span(String input, Map<String, Object> metadata) {
        return new RawEntry("s1", "llm", input, null, null, metadata, null, "t1", "2026-01-01T00:00:00Z", "chat");
    }

    private static RawEntry expected() {
        return span("contact [REDACTED_EMAIL] " + FILLER, new LinkedHashMap<>(Map.of("note", "[REDACTED_EMAIL]")));
    }

    private static RawEntry big(CountDownLatch release, @Nullable Throwable failure) {
        return span(
                "contact jane@example.com " + FILLER,
                new PoolSensitiveMetadata(Map.of("note", "bob@example.org"), release, failure));
    }

    /** Wait until {@code caller} is parked with its interrupt flag consumed, i.e. back inside a wait. */
    private static void awaitParked(Thread caller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (caller.getState() != Thread.State.WAITING || caller.isInterrupted()) {
            if (System.nanoTime() > deadline) fail("the caller never parked on the redaction task");
            Thread.onSpinWait();
        }
    }

    /**
     * A batch the pool fails on or is interrupted over still ends the way the serial path would: redacted
     * when the worker's failure was an ordinary exception (the batch is redone on the caller), and the
     * caller's interrupt handed back rather than swallowed. An interrupt, even a repeated one, waits the
     * pool out instead of abandoning or duplicating its work.
     */
    @ParameterizedTest
    @CsvSource({"none, false", "none, true", "runtime, false", "runtime, true"})
    void redactBatch_aFailedOrInterruptedFanOutStillReturnsTheRedactedBatch(String failure, boolean interrupt)
            throws Exception {
        CountDownLatch release = new CountDownLatch(interrupt ? 1 : 0);
        Thread caller = Thread.currentThread();
        Thread driver = new Thread(() -> {
            if (!interrupt) return;
            awaitParked(caller);
            caller.interrupt(); // lands in the first wait
            awaitParked(caller);
            caller.interrupt(); // lands in the uninterruptible wait, which must keep waiting
            awaitParked(caller);
            release.countDown();
        });
        driver.start();

        List<RawEntry> out = svc.redactBatch(
                PID, List.of(big(release, "runtime".equals(failure) ? new IllegalStateException("x") : null)));

        driver.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(interrupt, Thread.interrupted(), "the interrupt is restored for the drainer");
        assertEquals(List.of(expected()), out);
    }

    /**
     * An Error in the fan-out is rethrown as itself, never retried serially: a regex that overflowed the
     * stack on a worker would overflow it again. The fork/join wrapper rides along as suppressed.
     */
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void redactBatch_anErrorInTheFanOutPropagatesAsItself(boolean interrupt) {
        CountDownLatch release = new CountDownLatch(interrupt ? 1 : 0);
        Thread caller = Thread.currentThread();
        Thread driver = new Thread(() -> {
            if (!interrupt) return;
            awaitParked(caller);
            caller.interrupt();
            awaitParked(caller);
            release.countDown();
        });
        driver.start();

        StackOverflowError e = assertThrows(
                StackOverflowError.class, () -> svc.redactBatch(PID, List.of(big(release, new StackOverflowError()))));

        assertEquals(interrupt, Thread.interrupted());
        assertInstanceOf(ExecutionException.class, e.getSuppressed()[0], "thrown from the fan-out, not a retry");
    }

    @Test
    void redactBatch_afterThePoolIsClosedRunsOnTheCaller() {
        // @PreDestroy closes the pool while a drainer can still hold a claimed batch.
        svc.shutdownPool();

        List<RawEntry> out = svc.redactBatch(PID, List.of(big(new CountDownLatch(0), null)));

        assertEquals(List.of(expected()), out);
    }

    // ---- built-in reconciliation ---------------------------------------------------------------

    /**
     * A built-in whose pattern, replacement or order has since been corrected is re-pointed at the platform's
     * version, keeping its id, its creation time and, above all, the operator's enabled choice; one that is
     * already current is left alone.
     */
    @ParameterizedTest
    @CsvSource({"pattern", "replacement", "sortOrder", "none"})
    void listRules_repointsAStaleBuiltInButKeepsItDisabled(String stale) {
        BuiltInRedactionRules.Template t = BuiltInRedactionRules.TEMPLATES.get(1);
        RedactionRuleRow before = new RedactionRuleRow(
                "rule-1",
                PID,
                t.name(),
                "pattern".equals(stale) ? "old-pattern" : t.pattern(),
                "replacement".equals(stale) ? "[OLD]" : t.replacement(),
                false,
                true,
                "sortOrder".equals(stale) ? t.sortOrder() + 1 : t.sortOrder(),
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z");
        repo.insert(before);

        svc.listRules(PID);

        RedactionRuleRow after = repo.named(t.name());
        assertEquals(
                new RedactionRuleRow(
                        "rule-1",
                        PID,
                        t.name(),
                        t.pattern(),
                        t.replacement(),
                        false,
                        true,
                        t.sortOrder(),
                        "2026-01-01T00:00:00Z",
                        "none".equals(stale) ? "2026-01-01T00:00:00Z" : after.updatedAt()),
                after);
        assertEquals(
                BuiltInRedactionRules.TEMPLATES.size(), repo.findByProject(PID).size(), "no duplicate row");
    }

    // ---- playground ---------------------------------------------------------------------------

    @Test
    void builtInRules_canBeSwitchedOffButNeitherEditedNorDeleted() {
        String id = svc.listRules(PID).get(0).id();

        assertEquals(
                RedactionError.BUILT_IN_IMMUTABLE,
                assertThrows(TessaryException.class, () -> svc.updateRule(PID, id, "n", "x", "y", true, 1))
                        .error());
        assertEquals(
                RedactionError.BUILT_IN_IMMUTABLE,
                assertThrows(TessaryException.class, () -> svc.deleteRule(PID, id))
                        .error());
        assertEquals(false, svc.setEnabled(PID, id, false).enabled());
        assertEquals(false, repo.findById(PID, id).orElseThrow().enabled());
    }

    /** A rule id from another project, or none at all, is not found; a regex that does not compile is refused. */
    @ParameterizedTest
    @CsvSource({
        "update-missing, RULE_NOT_FOUND",
        "toggle-missing, RULE_NOT_FOUND",
        "delete-missing, RULE_NOT_FOUND",
        "create-bad-regex, INVALID_PATTERN",
        "update-bad-regex, INVALID_PATTERN",
        "preview-bad-regex, INVALID_PATTERN"
    })
    void playground_refusesUnknownRulesAndBrokenPatterns(String op, RedactionError expected) {
        String custom =
                svc.createRule(PID, "Ticket", "TKT-\\d+", "[TICKET]", true, 100).id();
        Runnable call =
                switch (op) {
                    case "update-missing" -> () -> svc.updateRule(PID, "nope", "n", "x", "y", true, 1);
                    case "toggle-missing" -> () -> svc.setEnabled(PID, "nope", false);
                    case "delete-missing" -> () -> svc.deleteRule("other-project", custom);
                    case "create-bad-regex" -> () -> svc.createRule(PID, "n", "(unclosed", "y", true, 1);
                    case "update-bad-regex" -> () -> svc.updateRule(PID, custom, "n", "(unclosed", "y", true, 1);
                    default -> () -> svc.preview("(unclosed", "y", "sample");
                };

        assertEquals(expected, assertThrows(TessaryException.class, call::run).error());
    }

    @Test
    void customRules_areEditedAndDeleted_andTheGuardSeesEachChange() {
        String id =
                svc.createRule(PID, "Ticket", "TKT-\\d+", "[TICKET]", true, 100).id();
        assertEquals("[TICKET] open", svc.previewAll(PID, "TKT-12 open").redacted());

        svc.updateRule(PID, id, "Ticket", "TKT-\\d+", "[T]", true, 100);
        assertEquals("[T] open", svc.previewAll(PID, "TKT-12 open").redacted(), "the cached rule set is invalidated");

        svc.deleteRule(PID, id);
        assertEquals("TKT-12 open", svc.previewAll(PID, "TKT-12 open").redacted());
    }

    /** One unsaved rule, or the whole active set with every rule's matches counted, including the corpus's. */
    @Test
    void preview_countsMatchesForOneRuleOrTheWholeActiveSet() {
        assertEquals(new PreviewResult("a1 b22 c", "a# b# c", 2), svc.preview("\\d+", "#", "a1 b22 c"));
        // Split so this repo's own secret scan does not read a fake AWS key id as a leak.
        String key = "AKIA" + "QYLPMN5HHHFPZAM2";
        // Three matches on the sample as sent: the email rule, the credential corpus, and the provider-key
        // rule, which also recognises an AKIA id; the corpus runs first, so its token is the one kept.
        assertEquals(
                new PreviewResult("mail x@example.com key " + key, "mail [REDACTED_EMAIL] key [REDACTED_SECRET]", 3),
                svc.previewAll(PID, "mail x@example.com key " + key));
    }

    /** A null sample (a direct caller; the endpoint requires one) reads as nothing to redact, not a crash. */
    @Test
    void preview_ofNoSampleIsEmpty() {
        assertEquals(new PreviewResult(null, null, 0), svc.preview("\\d+", "#", null));
        assertEquals(new PreviewResult(null, null, 0), svc.previewAll(PID, null));
    }
}
