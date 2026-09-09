// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import ai.tessary.config.RedactionProperties;
import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.RawEntry;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The PII redaction feature: authoring/testing of per-project redaction rules (the
 * playground) and the server-side write-path guard that strips PII from a {@link RawEntry} batch before it
 * is persisted to the agent-native substrate.
 *
 * <p><b>Write-path guard.</b> {@link #redactBatch} is called by the {@code SubstrateWriter} DRAINER,
 * immediately before the substrate write, downstream of the single chokepoint every ingest source (OTLP,
 * pull, run, signal) tees through — so redaction happens once, off the request thread, regardless of how a
 * span arrived. It deliberately does NOT run in {@code enqueue}: doing so put full regex over every entry
 * on the HTTP request thread and saturated production on 2026-07-31. It applies a project's enabled rules to the free-text,
 * PII-bearing fields ({@code input}, {@code output}, the two {@code *MessagesJson} payloads, and string
 * values in metadata), returning a new {@link RawEntry} with redacted content. Structural fields (ids,
 * timestamps, model, kind) are untouched. Running here rather than deeper also means the {@code trace}
 * preview columns are redacted by construction: {@code SpanBatchWriter} cuts them from content this has
 * already rewritten.
 *
 * <p><b>Redaction is a declared transform, not a silent truncation.</b> The never-truncate rule forbids
 * silently clipping telemetry; redaction is the opposite — an explicit, operator-authored substitution. A
 * rule whose regex fails to compile is skipped at compile time (never applied, never throws on the hot
 * path), and the master {@code tessary.redaction.enabled} switch is the kill switch.
 *
 * <p><b>Compiled-rule cache.</b> Per-project compiled rule sets are cached and invalidated on any rule
 * mutation, so the hot path costs no DB read once warm. A project with no rules (or with the guard disabled)
 * is a cheap no-op that returns the input batch unchanged.
 */
@Service
public class RedactionService {

    private static final Logger log = LoggerFactory.getLogger(RedactionService.class);

    private final RedactionRuleRepository repo;
    private final RedactionProperties props;

    private final Map<String, List<CompiledRule>> compiledCache = new ConcurrentHashMap<>();

    /**
     * Below this many payload bytes the fork and join cost more than the regex they save.
     *
     * <p>Bytes rather than entries, because {@link #redactBatch} states the cost model in its own log
     * line: redaction scales with body size, not row count. Sixteen kilobytes is roughly four of the
     * ~4 KB chat spans the ingest path actually carries.
     */
    private static final long PARALLEL_THRESHOLD_BYTES = 16 * 1024;

    /**
     * The pool redaction runs in, or null when {@code parallelism} is 1 and the calling thread does the
     * work — which is the pre-2026-09 behaviour exactly.
     *
     * <p>Owned here rather than borrowed from the common pool: this is the only CPU-bound fan-out in the
     * write path, and the whole reason redaction sits on the drain side is that its cost must stay
     * bounded and off the request thread. A pool of a stated size keeps both properties; the common pool
     * would share threads with anything else that parallel-streams and make the bound a fiction.
     */
    private final @Nullable ForkJoinPool pool;

    public RedactionService(RedactionRuleRepository repo, RedactionProperties props) {
        // Null-tolerant: the resilience tests build this with no properties to exercise the writer, and
        // no configuration means no fan-out, which is the serial behaviour they are asserting anyway.
        int parallelism = props == null ? 1 : props.getParallelism();
        // Named threads: this codebase diagnoses production by thread dump, and "ForkJoinPool-1-worker-3"
        // says nothing about which subsystem is burning the core. A counter rather than getPoolIndex():
        // the pool assigns that index in registerWorker, AFTER this factory returns, so naming from it
        // labels every worker "redaction-0" and buys nothing.
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

    // ----- write-path guard -------------------------------------------------------------------------

    /**
     * Redact a project's ingest batch before substrate persistence. Returns the same list reference when
     * the guard is disabled or the project has no enabled rules (the common no-op); otherwise a new list of
     * redacted {@link RawEntry}s. A malformed rule is already dropped at compile time, so no rule can fail
     * here; the one thing this does propagate is an {@link Error} raised inside the fan-out, which is
     * deliberately not caught (see {@link #redactAll}).
     */
    public List<RawEntry> redactBatch(String projectId, List<RawEntry> entries) {
        if (!props.isEnabled() || entries.isEmpty()) return entries;
        List<CompiledRule> rules = compiledFor(projectId);
        if (rules.isEmpty()) return entries;
        // Timed because this is regex over every ingested body and its cost scales with body size,
        // not entry count — the two numbers that matter are therefore bytes and duration, not rows.
        // Before this existed the whole package logged nothing, so when a pathological rule pinned
        // both production vCPUs on 2026-07-31 there was no log line anywhere pointing at redaction;
        // it took a CPU profile to find. durationMs/bytes are emitted as numeric fields so Loki can
        // graph them and alert on the trend instead of anyone needing to notice.
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
     * Apply the rules to every entry, across {@link RedactionProperties#getParallelism()} threads.
     *
     * <p>Each entry is an independent pure transform over immutable compiled rules, so this is a plain
     * parallel map; {@code toList()} on an ordered stream keeps the batch's order. The writer does not
     * require that — it re-sorts by {@code (traceId, id)} itself and its §6.1 says every FK is
     * satisfiable regardless of arrival order — but a transform that returns rows in the order it was
     * given is one less thing to reason about, and costs nothing here. The work is submitted into a pool this class owns rather than the
     * common pool, so redaction can never borrow threads from — or lend contention to — anything else,
     * and the parallelism stays the stated bound.
     *
     * <p>Small batches stay on the calling thread: below {@link #PARALLEL_THRESHOLD_BYTES} the fork and
     * join cost more than the regex saves.
     */
    private List<RawEntry> redactAll(List<RawEntry> entries, List<CompiledRule> rules, long bytes, boolean[] forked) {
        // isShutdown, because @PreDestroy can close the pool while a drainer still holds a claimed batch
        // (SubstrateWriter.shutdown does not join them) and submit would then throw
        // RejectedExecutionException out of a method documented as never throwing.
        if (pool == null || pool.isShutdown() || bytes < PARALLEL_THRESHOLD_BYTES) {
            return serially(entries, rules);
        }
        forked[0] = true;
        ForkJoinTask<List<RawEntry>> task;
        try {
            task = pool.submit(() ->
                    entries.parallelStream().map(e -> redactEntry(e, rules)).toList());
        } catch (RejectedExecutionException e) {
            // The isShutdown check above is advisory: @PreDestroy can land between it and this submit.
            forked[0] = false;
            return serially(entries, rules);
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            // Wait it out rather than cancel-and-redo. cancel(mayInterruptIfRunning) has no effect on a
            // running ForkJoinTask, so the pool completes this batch either way; redoing it serially
            // would run a second full pass over the same cores on the shutdown path, which is the worst
            // moment to double the work. The interrupt is carried to the caller instead of swallowed.
            try {
                return awaitUninterruptibly(task, entries, rules);
            } finally {
                Thread.currentThread().interrupt();
            }
        } catch (ExecutionException e) {
            // An Error is NOT ours to swallow. ForkJoinTask.get wraps one in ExecutionException, so a
            // bare catch here would quietly turn a StackOverflowError -- the live case, from regex
            // backtracking over payloads the body cap allows up to 2 MB -- into a serial retry that
            // overflows again. SubstrateWriter's own catch is "deliberately NOT widened to Throwable"
            // for the same reason; this keeps that decision rather than inverting it one layer down.
            if (e.getCause() instanceof Error err) {
                // Rethrown as-is, not wrapped: the point is that an Error keeps its type and reaches the
                // JVM's own handling. The wrapper carries the fork/join frames, so it rides along as
                // suppressed rather than being discarded.
                err.addSuppressed(e);
                throw err;
            }
            // Categorical, with no throwable attached and no cause message interpolated: WARN egresses
            // to Loki, and an exception raised while redacting can carry the very content redaction
            // exists to keep out of logs (backend/AGENTS.md, "WARN+ egress is a data-egress surface").
            forked[0] = false;
            StructuredLog.warn(log, Markers.OPS, "redaction.parallel.failed")
                    .field("entries", entries.size())
                    .field(
                            "cause",
                            e.getCause() == null
                                    ? "unknown"
                                    : e.getCause().getClass().getSimpleName())
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
     * The content {@link #redactEntry} actually rewrites, which is what its cost scales with.
     *
     * <p>All five, and the fifth is the one that keeps catching this out. {@code metadata} is not a few
     * tags: the OTLP mapper hands over the resource attributes plus every flattened span attribute, and
     * {@code redactMetadata} runs the rules over every non-usage string in it. Omitting it under-read a
     * normal LLM span roughly twofold — the messages sit in {@code metadata} as well until
     * {@code SpanBatchWriter} strips the duplicate — and read a span carrying its payload only in custom
     * attributes as zero, so that shape could never fork at any configured parallelism.
     *
     * <p>Two earlier versions of this method were wrong in the same direction: entry count (ignores size
     * entirely) and then the {@code *MessagesJson} pair alone (null on every non-OTLP source). The gate
     * fails safe to serial when this under-reads, which is why neither was caught by a test.
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

    /** Finish what the pool is already doing, ignoring further interrupts; the caller restores the flag. */
    private List<RawEntry> awaitUninterruptibly(
            ForkJoinTask<List<RawEntry>> task, List<RawEntry> entries, List<CompiledRule> rules) {
        while (true) {
            try {
                return task.get();
            } catch (InterruptedException ignored) {
                // Deliberately swallowed here and re-raised by the caller's finally: the point of this
                // loop is to finish work the pool is already doing rather than abandon and repeat it.
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof Error err) {
                    err.addSuppressed(ee);
                    throw err;
                }
                StructuredLog.warn(log, Markers.OPS, "redaction.parallel.failed")
                        .field("entries", entries.size())
                        .field(
                                "cause",
                                ee.getCause() == null
                                        ? "unknown"
                                        : ee.getCause().getClass().getSimpleName())
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

    private RawEntry redactEntry(RawEntry e, List<CompiledRule> rules) {
        return new RawEntry(
                e.sourceExternalId(),
                e.sourceUrl(),
                e.name(),
                redact(e.input(), rules),
                redact(e.output(), rules),
                e.model(),
                redactMetadata(e.metadata(), rules),
                e.parentId(),
                e.traceId(),
                e.timestamp(),
                e.operationKind(),
                e.endTimestamp(),
                redact(e.inputMessagesJson(), rules),
                redact(e.outputMessagesJson(), rules));
    }

    /**
     * Redact one content field.
     *
     * <p>{@code applyToJson}, not {@code applyToTextParts}: a rule that substitutes a bare token into a
     * JSON <em>number</em> position produces a document that no longer parses, and the typed column
     * downstream then silently goes null. {@code applyToJson} walks the parsed tree and only ever hands a
     * rule a string, falling through to {@code applyToTextParts} — media segmenting and all — for
     * anything that is not a JSON container, so a payload of prose is treated exactly as before.
     *
     * <p><b>Every content field takes the same path, deliberately.</b> {@code input} is often not JSON
     * while {@code inputMessagesJson} always is, so it would be tempting to route only the latter
     * structurally. That would break something quiet: {@code SpanBatchWriter} strips an attribute whose
     * value is byte-for-byte identical to the promoted column, and two fields carrying the same document
     * through two different redactors would stop being identical the moment a rule fired on both.
     */
    private @Nullable String redact(@Nullable String text, List<CompiledRule> rules) {
        return RedactionEngine.applyToJson(text, rules);
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
            @Nullable Map<String, Object> metadata, List<CompiledRule> rules) {
        if (metadata == null || metadata.isEmpty()) return metadata;
        Map<String, Object> out = new LinkedHashMap<>(metadata.size());
        boolean changed = false;
        for (Map.Entry<String, Object> kv : metadata.entrySet()) {
            Object v = kv.getValue();
            if (v instanceof String s && !GenAiAttributes.isUsageOrCostKey(kv.getKey())) {
                String r = RedactionEngine.applyToJson(s, rules);
                if (!s.equals(r)) changed = true;
                out.put(kv.getKey(), r);
            } else {
                out.put(kv.getKey(), v);
            }
        }
        return changed ? out : metadata;
    }

    /**
     * The project's compiled rule set, seeding the platform defaults on the way if this project has never
     * had them reconciled.
     *
     * <p><b>The seed used to live only on {@link #listRules}</b> — the Settings page read — which meant the
     * write-path guard applied whatever rules a project happened to have, and a project whose owner never
     * opened Settings → PII redaction had <em>none</em>. Every default this platform ships was, in practice,
     * opt-in by clicking. Seeding here instead makes the guard's rule set a property of ingesting rather
     * than of browsing: one extra read per project per process, on a cache miss, off the request thread.
     */
    private List<CompiledRule> compiledFor(String projectId) {
        // ensureSeeded MUST NOT invalidate the cache — this runs inside computeIfAbsent, and a
        // ConcurrentHashMap.remove on the key being computed is a recursive update.
        return compiledCache.computeIfAbsent(projectId, pid -> {
            ensureSeeded(pid);
            return RedactionEngine.compileAll(repo.findEnabledByProject(pid));
        });
    }

    private void invalidate(String projectId) {
        compiledCache.remove(projectId);
    }

    // ----- playground / CRUD ------------------------------------------------------------------------

    /** All rules for a project, reconciling the platform defaults onto it first. */
    public List<RedactionRuleRow> listRules(String projectId) {
        if (ensureSeeded(projectId)) invalidate(projectId);
        return repo.findByProject(projectId);
    }

    /**
     * Reconcile this project's built-in rules against {@link BuiltInRedactionRules}, matched <b>by name</b>:
     * insert what is missing, and re-point a built-in whose pattern or replacement has since been corrected.
     *
     * <p><b>Insert-what-is-missing</b> rather than short-circuiting on "has any rule at all" is what lets a
     * new default reach projects that already exist. The old form seeded once and never again, so adding a
     * template would have protected new projects only — precisely backwards, since the projects with traffic
     * are the old ones.
     *
     * <p><b>Re-point-what-changed</b> exists because a built-in's pattern is the platform's, not the
     * project's: a rule can be disabled but never edited, so there is no operator intent to overwrite. Without
     * it a *correction* would reach nobody. That is not hypothetical — the credit-card default matched the
     * sixteen fractional digits of a float and quietly rewrote retrieval scores, and every project that had
     * ever loaded the settings page carried the broken copy.
     *
     * <p>What is deliberately NOT reconciled is {@code enabled}: that one IS the operator's, and a project
     * that turned a default off must not have it turned back on by a deploy.
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

    /** Toggle a rule's enabled flag — the only mutation permitted on a built-in rule. */
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

    /**
     * Playground preview: apply a single (possibly unsaved) rule to sample text and report the redacted
     * result plus match count. Rejects an invalid regex with {@link RedactionError#INVALID_PATTERN}.
     */
    public PreviewResult preview(String pattern, String replacement, String sampleText) {
        requireValidRegex(pattern);
        CompiledRule rule = RedactionEngine.compile("preview", pattern, replacement);
        if (rule == null) throw new TessaryException(RedactionError.INVALID_PATTERN, pattern);
        String redacted = RedactionEngine.applyToTextParts(sampleText, List.of(rule));
        int matches = RedactionEngine.countMatches(sampleText, rule);
        return new PreviewResult(sampleText, redacted == null ? sampleText : redacted, matches);
    }

    /**
     * Playground preview against the WHOLE active rule set: apply every enabled rule to sample text. Lets an
     * operator verify the combined effect of a project's redaction policy.
     */
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

    /** Result of a playground preview: original + redacted text and the match count. */
    public record PreviewResult(String original, String redacted, int matches) {}
}
