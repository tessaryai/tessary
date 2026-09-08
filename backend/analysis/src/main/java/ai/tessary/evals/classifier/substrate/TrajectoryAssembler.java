// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.substrate;

import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository.TraceAction;
import ai.tessary.evals.classifier.substrate.BehaviorSubstrateRepository.TraceHead;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Assembles what the behaviour-drift classifier scores: one trace reduced to its ordered
 * {@link ActionSymbol} sequence. The structural counterpart of {@link ConversationThreadAssembler} —
 * same "read the substrate, produce the scored input" role, but reducing to symbols rather than to
 * rendered text, because this classifier judges the SHAPE of what the agent did, not what was said.
 *
 * <p>The rare-name floor is supplied by the caller from the epoch, never derived from the batch: a
 * batch-local floor makes the same tool map to {@code tool:foo} in one sweep and {@code tool:__rare__}
 * in the next, which silently rewrites the alphabet the counts are keyed on.
 *
 * <p>Reduction is not a per-action map. A trace is a branching process flattened into a list, and the
 * flattening invents an order wherever the agent requested several actions at once. {@link #reduce}
 * puts that back: each concurrent batch becomes a fan-out block (§2.4), so every emission order of one
 * batch produces the same sequence. The grammar is pinned across both implementations by
 * {@code fixtures/reduction_contract.json}.
 */
@Component
public class TrajectoryAssembler {

    /**
     * The kinds one LLM call can dispatch concurrently, and so the only kinds admitted to a fan-out
     * block. Every other kind in the closed 14-value typology — {@code agent}, {@code workflow},
     * {@code handoff}, {@code guardrail}, {@code reasoning}, {@code memory}, {@code plan},
     * {@code step} — is control flow or commentary around a batch rather than a member of one, and
     * bounds the block instead of joining it.
     */
    private static final Set<String> DISPATCHABLE_KINDS = Set.of("tool", "mcp", "retrieval", "embedding", "reranker");

    private final BehaviorSubstrateRepository substrate;

    public TrajectoryAssembler(BehaviorSubstrateRepository substrate) {
        this.substrate = substrate;
    }

    /** Reduce a batch of traces to trajectories under the epoch's rare-symbol set, index order preserved. */
    public List<Trajectory> assemble(String projectId, List<TraceHead> heads, Set<String> rareSymbols) {
        if (heads.isEmpty()) return List.of();
        Map<String, List<String>> byTrace = symbolsByTrace(projectId, heads, rareSymbols);
        List<Trajectory> out = new ArrayList<>(heads.size());
        for (TraceHead head : heads) {
            List<String> symbols = byTrace.getOrDefault(head.traceId(), List.of());
            out.add(new Trajectory(
                    head.traceId(),
                    head.subjectSessionId(),
                    head.sessionId(),
                    head.projectVersionId(),
                    ActionSymbol.pad(symbols),
                    head.eventAt(),
                    head.eventAt()));
        }
        return out;
    }

    /**
     * Re-measure the below-floor symbol set over a bounded recent sample — the fit job's job, so the
     * alphabet is a property of the epoch rather than of whichever batch happened to be swept.
     */
    public Set<String> fitRareSymbols(String projectId, List<TraceHead> heads, double rareFloorFraction) {
        if (heads.isEmpty()) return Set.of();
        List<String> corpus = new ArrayList<>();
        // Measured with NO floor applied: a floor fitted over already-collapsed symbols would count
        // `__rare__` as the frequent name and re-floor whatever survived it.
        for (List<String> symbols : symbolsByTrace(projectId, heads, Set.of()).values()) corpus.addAll(symbols);
        return ActionSymbol.rareSymbols(corpus, rareFloorFraction);
    }

    /** One substrate read per batch, grouped by trace in the heads' order. */
    private Map<String, List<TraceAction>> groupActions(String projectId, List<TraceHead> heads) {
        Map<String, List<TraceAction>> actionsByTrace = new LinkedHashMap<>();
        for (TraceHead head : heads) actionsByTrace.put(head.traceId(), new ArrayList<>());
        List<String> traceIds = heads.stream().map(TraceHead::traceId).toList();
        for (TraceAction action : substrate.actionsForTraces(projectId, traceIds)) {
            List<TraceAction> actions = actionsByTrace.get(action.traceId());
            if (actions != null) actions.add(action);
        }
        return actionsByTrace;
    }

    private Map<String, List<String>> symbolsByTrace(String projectId, List<TraceHead> heads, Set<String> rareSymbols) {
        Map<String, List<String>> byTrace = new LinkedHashMap<>();
        groupActions(projectId, heads)
                .forEach((traceId, actions) -> byTrace.put(traceId, reduce(actions, rareSymbols)));
        return byTrace;
    }

    /**
     * Reduce one trace's ordered actions to symbols, collapsing each concurrent batch into a fan-out
     * block (§2.4). Package-private and pure so the grammar can be asserted without a substrate.
     *
     * <p>A batch is what ONE LLM call requested. Timestamps are never consulted — a model emits all of
     * a turn's tool calls at once, so the run IS the turn, whereas the wall-clock order within it is
     * decided by whichever result landed first and carries no intent. That distinction is the whole
     * point: a batch's members have no order to preserve, so they are emitted sorted and every
     * emission order reduces to the same sequence.
     *
     * <p>The dispatching LLM span itself is never emitted. "The model asked for three tools" and
     * "three tools ran" are one event; a symbol for each counted it twice, and since every batch is
     * preceded by exactly one LLM span, that symbol was a constant occupying an n-gram slot — which
     * halved the effective context depth for nothing.
     *
     * <p><b>Membership is deliberately narrow, because the failure modes are asymmetric.</b> Missing a
     * real fan-out costs a false positive we already tolerate; inventing one silently destroys an
     * ordering nobody can recover. So a member must clear all three of {@link #isMember}'s tests, and
     * the batch stops at the first action that does not — everything else is emitted in order:
     *
     * <ul>
     *   <li><b>Resolved parentage.</b> A null parent never matches, not even another null. Ingest now
     *       repairs a parent that arrived in a later batch than its children, so a null parent is no
     *       longer the routine outcome it was — but it still means "unknown", not "no parent": a span
     *       ingested before that repair existed, or one whose parent never arrived at all, keeps it.
     *       Treating two unknowns as siblings would fold unrelated spans into one batch.
     *   <li><b>Sibling or child of the dispatcher.</b> A dispatched tool is recorded either alongside
     *       the LLM span or beneath it. Anything under a third parent belongs to a nested unit — a
     *       sub-agent's own work — and folding it in would both invent concurrency and let a genuine
     *       sibling escape the block.
     *   <li><b>A kind an LLM call can actually dispatch.</b> Control-flow and moderation spans are not
     *       batch members. Sorting a {@code guardrail} into one would make "the PII filter now runs
     *       after the refund" reduce identically to the compliant order — the exact class of signal
     *       this classifier exists to catch.
     * </ul>
     */
    static List<String> reduce(List<TraceAction> actions) {
        return reduce(actions, Set.of());
    }

    /**
     * The floor is applied per action, BEFORE a batch's members are sorted, because sorting is what
     * makes the collapse observable. Applied afterwards — over the finished sequence — two batches
     * with identical post-floor content still reduced two ways: {@code {tool:aaa, tool:mmm}} sorts
     * before {@code tool:mmm} and {@code {tool:zzz, tool:mmm}} after it, so once both rare names
     * became {@code tool:__rare__} the same batch had two spellings and the order-invariance this
     * section promises quietly did not hold for them.
     */
    /**
     * One reduced symbol, and the observation that produced it.
     *
     * <p>{@code observationId} is null for the symbols the reduction SYNTHESISES — the fan-out
     * bracket — because no single span produced them. Everything else names its source, which is what
     * lets a caller enrich a symbol from data the alphabet itself does not carry.
     */
    public record Symbol(String symbol, @Nullable String observationId) {}

    /**
     * The reduction, keeping each symbol's source span.
     *
     * <p>Kept as one implementation with two views rather than re-implementing the batching logic
     * beside each caller: that divergence is what {@code behavior_drift/PROGRAM.md} §12.5 records
     * invalidating a whole round of published numbers.
     */
    static List<Symbol> reduceWithSources(List<TraceAction> actions, Set<String> rareSymbols) {
        List<Symbol> out = new ArrayList<>(actions.size() + 2);
        int i = 0;
        while (i < actions.size()) {
            TraceAction action = actions.get(i);
            if (!isLlm(action)) {
                out.add(new Symbol(symbolOf(action, rareSymbols), action.observationId()));
                i++;
                continue;
            }
            int end = batchEnd(actions, i);
            int width = end - (i + 1);
            if (width == 0) {
                // An ANSWERING llm span: the one place the agent speaks, and so the one place a
                // dialogue act belongs.
                out.add(new Symbol(symbolOf(action, rareSymbols), action.observationId()));
            } else if (width == 1) {
                TraceAction lone = actions.get(i + 1);
                out.add(new Symbol(symbolOf(lone, rareSymbols), lone.observationId()));
            } else {
                out.add(new Symbol(ActionSymbol.fork(width), null));
                actions.subList(i + 1, end).stream()
                        .map(a -> new Symbol(symbolOf(a, rareSymbols), a.observationId()))
                        .sorted(java.util.Comparator.comparing(Symbol::symbol))
                        .forEach(out::add);
                out.add(new Symbol(ActionSymbol.JOIN, null));
            }
            i = end;
        }
        return out;
    }

    static List<String> reduce(List<TraceAction> actions, Set<String> rareSymbols) {
        List<String> out = new ArrayList<>(actions.size() + 2);
        int i = 0;
        while (i < actions.size()) {
            TraceAction action = actions.get(i);
            if (!isLlm(action)) {
                out.add(symbolOf(action, rareSymbols));
                i++;
                continue;
            }
            int end = batchEnd(actions, i);
            int width = end - (i + 1);
            if (width == 0) {
                out.add(symbolOf(action, rareSymbols)); // answered rather than dispatched
            } else if (width == 1) {
                out.add(symbolOf(actions.get(i + 1), rareSymbols)); // a lone call has no order to lose
            } else {
                out.add(ActionSymbol.fork(width));
                actions.subList(i + 1, end).stream()
                        .map(a -> symbolOf(a, rareSymbols))
                        .sorted()
                        .forEach(out::add);
                out.add(ActionSymbol.JOIN);
            }
            i = end;
        }
        return out;
    }

    /** One past the last member of the batch dispatched by {@code actions.get(dispatcher)}. */
    private static int batchEnd(List<TraceAction> actions, int dispatcher) {
        int end = dispatcher + 1;
        while (end < actions.size() && isMember(actions.get(end), actions.get(dispatcher))) end++;
        return end;
    }

    private static boolean isMember(TraceAction candidate, TraceAction dispatcher) {
        String parent = candidate.parentObservationId();
        if (parent == null) return false;
        if (!DISPATCHABLE_KINDS.contains(kindOf(candidate))) return false;
        return parent.equals(dispatcher.parentObservationId()) || parent.equals(dispatcher.observationId());
    }

    /**
     * The normalised kind, read back off the symbol rather than off the raw column, so the predicate
     * and the emitted symbol can never disagree. Deriving it independently let {@code "llm-1"} open a
     * fan-out on one side while emitting a literal symbol on the other — a whole-sequence divergence
     * from one span, invisible to a fixture built from exact kinds.
     */
    private static String kindOf(TraceAction action) {
        return ActionSymbol.kindOf(symbolOf(action));
    }

    private static boolean isLlm(TraceAction action) {
        return ActionSymbol.LLM_KIND.equals(kindOf(action));
    }

    private static String symbolOf(TraceAction action) {
        return ActionSymbol.of(action.kind(), action.name(), action.isError());
    }

    /** The action's symbol with the epoch's rare-name floor already applied. */
    private static String symbolOf(TraceAction action, Set<String> rareSymbols) {
        String symbol = symbolOf(action);
        return rareSymbols.contains(symbol) ? ActionSymbol.collapseRare(symbol) : symbol;
    }
}
