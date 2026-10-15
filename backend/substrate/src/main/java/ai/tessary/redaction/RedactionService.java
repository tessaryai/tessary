// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import ai.tessary.config.RedactionProperties;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.RedactionStamp;
import ai.tessary.open.errors.RedactionError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.redaction.RedactionEngine.CompiledRule;
import ai.tessary.tenant.Ids;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PII redaction: the rule playground and the write-path guard. {@link #redactBatch} runs on the {@code SubstrateWriter}
 * DRAINER, never in {@code enqueue}, after regex on the request thread saturated production on 2026-07-31. Redaction is
 * a declared, operator-authored substitution, not truncation; a rule that fails to compile is skipped. Compiled rules
 * are cached per project and invalidated on any mutation.
 */
@Service
public class RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionService.class);

    private final RedactionRuleRepository repo;
    private final RedactionProperties props;

    private final Map<String, List<CompiledRule>> compiledCache = new ConcurrentHashMap<>();

    /**
     * Bytes rather than entries, because redaction cost scales with body size; 16 KB is about four typical chat spans.
     */
    private static final long PARALLEL_THRESHOLD_BYTES = 16 * 1024;

    /**
     * Null when {@code parallelism} is 1. Owned rather than the common pool so redaction's CPU stays a stated bound off
     * the request thread.
     */
    private final @Nullable ForkJoinPool pool;

    public RedactionService(RedactionRuleRepository repo, RedactionProperties props) {
        // Null-tolerant: the resilience tests build this with no properties.
        int parallelism = props == null ? 1 : props.parallelism();
        // Named threads for thread dumps. A counter, because the pool assigns getPoolIndex() only after this factory
        // returns.
        AtomicInteger seq = new AtomicInteger();
        this.pool = parallelism > 1
                ? new ForkJoinPool(
                        parallelism,
                        p -> {
                            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                            t.setName("redaction-" + seq.getAndIncrement());
                            return t;
                        },
                        null,
                        false)
                : null;
        this.repo = repo;
        this.props = props;
    }

    /**
     * Returns the same list when there is nothing to apply. An {@link Error} inside the fan-out is deliberately not
     * caught (see {@link #redactAll}).
     */
    public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
        if (!props.enabled() || entries.isEmpty()) return entries;
        List<CompiledRule> rules = compiledFor(projectId);
        if (rules.isEmpty()) return entries;
        // Timed with bytes and duration as numeric fields: when a pathological rule pinned both production vCPUs
        // nothing pointed at redaction.
        Instant started = Instant.now();
        long bytes = 0;
        for (RawEntry e : entries) {
            bytes += redactableBytes(e);
        }
        boolean[] forked = {false};
        List<RawEntry> out = redactAll(entries, rules, bytes, forked);
        StructuredLog.info(log, Markers.OPS, "redaction.batch")
                .field("entries", entries.size())
                .field("rules", rules.size())
                .field("bytes", bytes)
                .field("parallel", forked[0])
                .durationMs(started)
                .log();
        return out;
    }

    /**
     * A parallel map over immutable compiled rules that keeps batch order, in a pool this class owns. Below
     * {@link #PARALLEL_THRESHOLD_BYTES} it stays on the calling thread.
     */
    private List<RawEntry> redactAll(List<RawEntry> entries, List<CompiledRule> rules, long bytes, boolean[] forked) {
        if (pool == null || bytes < PARALLEL_THRESHOLD_BYTES) {
            return serially(entries, rules);
        }
        forked[0] = true;
        ForkJoinTask<List<RawEntry>> task;
        try {
            task = pool.submit(() ->
                    entries.parallelStream().map(e -> redactEntry(e, rules)).toList());
        } catch (RejectedExecutionException e) {
            // @PreDestroy can close the pool while a drainer holds a batch, so catch the rejection; an isShutdown check
            // would only be advisory.
            forked[0] = false;
            return serially(entries, rules);
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            // Wait rather than cancel-and-redo: cancel has no effect on a running ForkJoinTask. The interrupt is
            // carried to the caller.
            try {
                return awaitUninterruptibly(task, entries, rules);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (ExecutionException e) {
            // An Error is NOT ours to swallow: a StackOverflowError from regex backtracking would otherwise be retried
            // serially and overflow again. SubstrateWriter keeps the same rule.
            if (e.getCause() instanceof Error err) {
                // Rethrown as-is so it keeps its type; the wrapper rides along as suppressed.
                err.addSuppressed(e);
                throw err;
            }
            // No throwable and no cause message: WARN egresses to Loki, and a redaction exception can carry the content
            // redaction exists to hide.
            forked[0] = false;
            StructuredLog.warn(log, Markers.OPS, "redaction.parallel.failed")
                    .field("entries", entries.size())
                    .field("cause", e.getCause().getClass().getSimpleName())
                    .log();
            log.debug("parallel redaction failure detail", e.getCause());
            return serially(entries, rules);
        }
    }

    @PreDestroy
    void shutdownPool() {
        if (pool != null) pool.shutdown();
    }

    /**
     * All five fields, {@code metadata} included: it carries every flattened span attribute (and the messages until
     * {@code SpanBatchWriter} strips them). Under-reading fails safe to serial, which is why earlier versions that did
     * were never caught.
     */
    private static long redactableBytes(RawEntry e) {
        long total = (long) length(e.input())
                + length(e.output())
                + length(e.inputMessagesJson())
                + length(e.outputMessagesJson());
        Map<String, Object> metadata = e.metadata();
        if (metadata != null) {
            for (Map.Entry<String, Object> kv : metadata.entrySet()) {
                if (kv.getValue() instanceof String v && !GenAiAttributes.isUsageOrCostKey(kv.getKey())) {
                    total += v.length();
                }
            }
        }
        return total;
    }

    private List<RawEntry> awaitUninterruptibly(
            ForkJoinTask<List<RawEntry>> task, List<RawEntry> entries, List<CompiledRule> rules) {
        while (true) {
            try {
                return task.get();
            } catch (InterruptedException ignored) {
                // Re-raised by the caller's finally: this finishes work the pool already started rather than repeating
                // it.
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof Error err) {
                    err.addSuppressed(ee);
                    throw err;
                }
                StructuredLog.warn(log, Markers.OPS, "redaction.parallel.failed")
                        .field("entries", entries.size())
                        .field("cause", ee.getCause().getClass().getSimpleName())
                        .log();
                return serially(entries, rules);
            }
        }
    }

    private List<RawEntry> serially(List<RawEntry> entries, List<CompiledRule> rules) {
        List<RawEntry> out = new ArrayList<>(entries.size());
        for (RawEntry e : entries) out.add(redactEntry(e, rules));
        return out;
    }

    private static int length(@Nullable String s) {
        return s == null ? 0 : s.length();
    }

    /** The copy passes every component through; see {@link RawEntry#eventTs}. */
    private RawEntry redactEntry(RawEntry e, List<CompiledRule> rules) {
        Set<RedactionStamp> stamps = new LinkedHashSet<>();
        List<RedactionStamp> earlier = e.redactions();
        if (earlier != null) stamps.addAll(earlier);
        BiConsumer<GitleaksCorpus.Finding, String> input = stampInto(stamps, RedactionStamp.INPUT);
        BiConsumer<GitleaksCorpus.Finding, String> output = stampInto(stamps, RedactionStamp.OUTPUT);
        return new RawEntry(
                e.sourceExternalId(),
                e.name(),
                redact(e.input(), rules, input),
                redact(e.output(), rules, output),
                e.model(),
                redactMetadata(e.metadata(), rules, stampInto(stamps, RedactionStamp.ATTRIBUTES)),
                e.parentId(),
                e.traceId(),
                e.timestamp(),
                e.operationKind(),
                e.endTimestamp(),
                redact(e.inputMessagesJson(), rules, input),
                redact(e.outputMessagesJson(), rules, output),
                stamps.isEmpty() ? null : List.copyOf(stamps));
    }

    private static BiConsumer<GitleaksCorpus.Finding, String> stampInto(Set<RedactionStamp> stamps, String field) {
        return (f, rawMatch) ->
                stamps.add(new RedactionStamp(f.ruleId(), field, f.anchored(), CredentialMasking.maskedKey(rawMatch)));
    }

    /**
     * {@code applyToJson}, so a rule substituting into a JSON number position cannot break the document. Every content
     * field takes this path: {@code SpanBatchWriter} strips attributes byte-identical to the promoted column, and two
     * redactors would break that identity.
     */
    private @Nullable String redact(
            @Nullable String text, List<CompiledRule> rules, BiConsumer<GitleaksCorpus.Finding, String> matched) {
        return RedactionEngine.applyToJson(text, rules, matched);
    }

    /**
     * Redact the string-valued attributes, EXCEPT the ones that are quantities.
     *
     * <p>A producer may send a token count or a cost as a string, and a redaction rule cannot tell that
     * string from free text: {@code gen_ai.usage.cost = "0.0123456789"} matches a phone-number rule on
     * digit count alone and comes out {@code [REDACTED_PHONE]}. Because this runs on the drain immediately
     * before the substrate write, the pricer downstream then reads a cost that is no longer a number,
     * falls through to inferring one, and records a figure the producer never reported — see
     * {@link GenAiAttributes#isUsageOrCostKey}, which owns the exemption and the worked example.
     *
     * <p>The exemption is by key, not by shape, and it is safe by those keys' own contract: each is
     * defined as an integer count or a decimal amount, so none is a place PII can be.
     */
    private @Nullable Map<String, Object> redactMetadata(
            @Nullable Map<String, Object> metadata,
            List<CompiledRule> rules,
            BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        Map<String, Object> out = new LinkedHashMap<>(metadata.size());
        boolean changed = false;
        for (Map.Entry<String, Object> kv : metadata.entrySet()) {
            Object v = kv.getValue();
            if (v instanceof String s && !GenAiAttributes.isUsageOrCostKey(kv.getKey())) {
                String r = RedactionEngine.applyToJson(s, rules, matched);
                if (!s.equals(r)) changed = true;
                out.put(kv.getKey(), r);
            } else {
                out.put(kv.getKey(), v);
            }
        }
        return changed ? out : metadata;
    }

    /**
     * Seeds the platform defaults on the ingest path, not only on the Settings read, so defaults apply to projects
     * whose owner never opened Settings.
     */
    private List<CompiledRule> compiledFor(String projectId) {
        // ensureSeeded must not invalidate the cache: removing the key being computed inside computeIfAbsent is a
        // recursive update.
        return compiledCache.computeIfAbsent(projectId, pid -> {
            ensureSeeded(pid);
            return RedactionEngine.compileAll(repo.findEnabledByProject(pid));
        });
    }

    private void invalidate(String projectId) {
        compiledCache.remove(projectId);
    }

    public List<RedactionRuleRow> listRules(String projectId) {
        if (ensureSeeded(projectId)) invalidate(projectId);
        return repo.findByProject(projectId);
    }

    /**
     * Reconcile built-in rules by name: insert what is missing (so a new default reaches existing projects) and
     * re-point a built-in whose pattern was corrected (a built-in cannot be edited, so there is no operator intent to
     * overwrite). {@code enabled} is never reconciled: a project that turned a default off keeps it off.
     */
    private boolean ensureSeeded(String projectId) {
        List<RedactionRuleRow> existing = repo.findByProject(projectId);
        Map<String, RedactionRuleRow> byName = new LinkedHashMap<>();
        for (RedactionRuleRow row : existing) {
            if (row.builtIn()) byName.put(row.name(), row);
        }
        String now = Instant.now().toString();
        boolean changed = false;
        for (BuiltInRedactionRules.Template t : BuiltInRedactionRules.TEMPLATES) {
            RedactionRuleRow current = byName.get(t.name());
            if (current == null) {
                repo.insert(new RedactionRuleRow(
                        Ids.ulid(),
                        projectId,
                        t.name(),
                        t.pattern(),
                        t.replacement(),
                        true,
                        true,
                        t.sortOrder(),
                        now,
                        now));
                changed = true;
            } else if (!current.pattern().equals(t.pattern())
                    || !current.replacement().equals(t.replacement())
                    || current.sortOrder() != t.sortOrder()) {
                repo.update(new RedactionRuleRow(
                        current.id(),
                        projectId,
                        t.name(),
                        t.pattern(),
                        t.replacement(),
                        current.enabled(),
                        true,
                        t.sortOrder(),
                        current.createdAt(),
                        now));
                changed = true;
            }
        }
        return changed;
    }

    public RedactionRuleRow createRule(
            String projectId, String name, String pattern, String replacement, boolean enabled, int sortOrder) {
        requireValidRegex(pattern);
        String now = Instant.now().toString();
        RedactionRuleRow row = new RedactionRuleRow(
                Ids.ulid(), projectId, name, pattern, replacement, enabled, false, sortOrder, now, now);
        repo.insert(row);
        invalidate(projectId);
        return row;
    }

    public RedactionRuleRow updateRule(
            String projectId,
            String id,
            String name,
            String pattern,
            String replacement,
            boolean enabled,
            int sortOrder) {
        RedactionRuleRow existing =
                repo.findById(projectId, id).orElseThrow(() -> new TessaryException(RedactionError.RULE_NOT_FOUND, id));
        if (existing.builtIn()) throw new TessaryException(RedactionError.BUILT_IN_IMMUTABLE, existing.name());
        requireValidRegex(pattern);
        RedactionRuleRow updated = new RedactionRuleRow(
                id,
                projectId,
                name,
                pattern,
                replacement,
                enabled,
                false,
                sortOrder,
                existing.createdAt(),
                Instant.now().toString());
        repo.update(updated);
        invalidate(projectId);
        return updated;
    }

    /** The only mutation permitted on a built-in rule. */
    public RedactionRuleRow setEnabled(String projectId, String id, boolean enabled) {
        RedactionRuleRow existing =
                repo.findById(projectId, id).orElseThrow(() -> new TessaryException(RedactionError.RULE_NOT_FOUND, id));
        repo.setEnabled(projectId, id, enabled, Instant.now().toString());
        invalidate(projectId);
        return new RedactionRuleRow(
                existing.id(),
                existing.projectId(),
                existing.name(),
                existing.pattern(),
                existing.replacement(),
                enabled,
                existing.builtIn(),
                existing.sortOrder(),
                existing.createdAt(),
                Instant.now().toString());
    }

    public void deleteRule(String projectId, String id) {
        RedactionRuleRow existing =
                repo.findById(projectId, id).orElseThrow(() -> new TessaryException(RedactionError.RULE_NOT_FOUND, id));
        if (existing.builtIn()) throw new TessaryException(RedactionError.BUILT_IN_IMMUTABLE, existing.name());
        repo.delete(projectId, id);
        invalidate(projectId);
    }

    /** Rejects an invalid regex with {@link RedactionError#INVALID_PATTERN}. */
    public PreviewResult preview(String pattern, String replacement, String sampleText) {
        // compile() is null exactly when the regex does not compile.
        CompiledRule rule = RedactionEngine.compile("preview", pattern, replacement);
        if (rule == null) throw new TessaryException(RedactionError.INVALID_PATTERN, pattern);
        String redacted = RedactionEngine.applyToTextParts(sampleText, List.of(rule));
        int matches = RedactionEngine.countMatches(sampleText, rule);
        return new PreviewResult(sampleText, redacted == null ? sampleText : redacted, matches);
    }

    public PreviewResult previewAll(String projectId, String sampleText) {
        List<CompiledRule> rules = compiledFor(projectId);
        String redacted = RedactionEngine.applyToTextParts(sampleText, rules);
        int matches = 0;
        for (CompiledRule rule : rules) matches += RedactionEngine.countMatches(sampleText, rule);
        return new PreviewResult(sampleText, redacted == null ? sampleText : redacted, matches);
    }

    private void requireValidRegex(String pattern) {
        if (!RedactionEngine.isValidRegex(pattern)) {
            throw new TessaryException(RedactionError.INVALID_PATTERN, pattern);
        }
    }

    public record PreviewResult(String original, String redacted, int matches) {}
}
