// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.substrate;

import ai.tessary.evals.model.ContentExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The Java mirror of the classifier context-serialization contract (v2) — the single, cross-language
 * definition of how a conversation thread is flattened into the text an encoder head scores. The
 * Python source of truth is {@code classifiers/framework/context.py}; both sides are pinned
 * byte-for-byte by the shared golden fixtures in {@code
 * classifiers/framework/fixtures/context_contract.json}. If this renderer diverges, serving feeds
 * the model input it never trained on — the exact train/serve skew the fixture catches.
 *
 * <p><b>Per-turn rendering.</b> Turns render oldest-first, one block per turn, each prefixed exactly
 * {@code [user] } or {@code [assistant] }, blocks separated by a single newline. User turns render
 * verbatim (stripped); assistant turns are capped at {@link #ASSISTANT_CAP_TOKENS} tokens ({@link
 * #capAssistant}) so an over-long agent turn cannot swamp the window. A lone user turn with no prior
 * context degrades to the bare stripped message — the exact single-turn input the incumbent head saw.
 *
 * <p><b>Multimodal + tool markers (v2).</b> A text encoder cannot see an image or a tool payload, but
 * it must know one was present, so non-text parts render as typed placeholders: {@code [image]} /
 * {@code [image: <caption>]}, {@code [file: <name>]} / {@code [file]}, {@code [unsupported]}, and — the
 * v2 enrichment — tool outcomes as terse markers instead of raw payloads: {@code [tool:<name> ok]} on
 * success, {@code [tool:<name> error: <snippet>]} on error (see {@link ContentExtractor#toolMarker}).
 * The tool argument/result blobs never reach the encoder; only the outcome does.
 *
 * <p><b>Trajectory-preserving reduction.</b> {@link #reduceThread} serializes a whole session within a
 * character budget by keeping the baseline head (earliest turn through the earliest failure marker —
 * the antecedent) and the most-recent K turns, thinning the redundant middle, and marking any
 * BUDGET-driven drop with {@code [… N earlier turns elided …]}. The scored final turn is never
 * dropped. A POLICY-driven drop (the narrowing below) is applied first and is not marked.
 *
 * <p><b>Narrowing for pooled heads.</b> {@link #windowByUserTurns} and {@link #stubAssistants} are the
 * two narrowing transforms a head may apply to its prior turns BEFORE reduction. They exist because a
 * single-utterance encoder (the GoEmotions family the frustration head uses) emits ONE score for the
 * whole string with no way to weight the trailing turn: every prepended block dilutes the message
 * actually being judged. Measured on 300 labelled production turns, AUC falls monotonically as context
 * grows — 0.838 bare, 0.830 at one exchange, 0.801 at three, 0.718 unbounded. A head that consumes
 * context positionally (a trained context-aware head) wants neither transform and should not set them.
 */
public final class ConversationThreadRenderer {

    static final String USER_PREFIX = "[user] ";
    static final String ASSISTANT_PREFIX = "[assistant] ";

    /**
     * What an assistant turn collapses to under {@link #stubAssistants}: the fact that the agent
     * replied, with none of its prose. The agent's own words are what a pooled emotion head scores
     * most wrongly — a sympathetic apology ("I understand your frustration") reads as the USER's
     * emotion — while their mere presence is what makes a terse reply ("ok fine") legible as a
     * reaction rather than an opening.
     */
    public static final String ASSISTANT_STUB = "[reply]";

    /** Assistant prose beyond this token estimate is capped (head + {@code …[+N tok]} marker). */
    public static final int ASSISTANT_CAP_TOKENS = 256;

    private static final int CHARS_PER_TOKEN = 4;

    /**
     * Marker inserted where the BUDGET elides {@code n} prior turns; a budget-driven drop is never
     * silent. A policy-driven drop ({@link #windowByUserTurns}) is deliberately unmarked — the head
     * asked for a fixed window, so an elision count would be noise in every single input rather than
     * evidence that something was lost.
     */
    static String elision(int n) {
        return "[… " + n + " earlier turns elided …]";
    }

    private ConversationThreadRenderer() {}

    /**
     * One turn: {@code speaker} in {@code {"user","assistant","tool"}}. For {@code user}/{@code
     * assistant} the {@code text} is the flattened turn text; for {@code tool} the {@code text} is the
     * tool name and {@code toolError} the error message (null = success), rendered to a terse marker.
     */
    public record Turn(
            String speaker, String text, @Nullable String toolError) {
        public Turn(String speaker, String text) {
            this(speaker, text, null);
        }

        public static Turn user(String text) {
            return new Turn("user", text, null);
        }

        public static Turn assistant(String text) {
            return new Turn("assistant", text, null);
        }

        public static Turn tool(@Nullable String name, @Nullable String error) {
            return new Turn("tool", name == null ? "" : name, error);
        }

        boolean isErrorMarker() {
            return "tool".equals(speaker) && toolError != null && !toolError.isBlank();
        }
    }

    private static String prefix(String speaker) {
        return switch (speaker) {
            case "user" -> USER_PREFIX;
            case "assistant" -> ASSISTANT_PREFIX;
            default -> throw new IllegalArgumentException("contract v2 prefixes only user/assistant, not " + speaker);
        };
    }

    /**
     * A deterministic, cross-language token estimate: {@code ceil(len / 4)} (~4 chars/token), where
     * {@code len} counts Unicode CODE POINTS — not UTF-16 units — so a non-BMP character (an emoji)
     * estimates identically to Python's {@code len(text)}. Counting {@code String.length()} would
     * over-count by one per surrogate pair and skew Java/Python at a truncation boundary.
     */
    static int estTokens(String text) {
        return (text.codePointCount(0, text.length()) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * Cap assistant prose at {@link #ASSISTANT_CAP_TOKENS}: under the cap it rides through stripped;
     * over it, the head is kept and a {@code …[+N tok]} marker records the dropped tail (N = estimated
     * tokens dropped). A dumb head-truncation — no summarization. Mirrors Python {@code cap_assistant}.
     *
     * <p>The budget is measured and split in CODE POINTS (Python slices {@code text[:budget]} by code
     * point), so an emoji straddling the boundary is kept or dropped whole — never split into a lone
     * surrogate half that diverges from the Python renderer.
     */
    static String capAssistant(String raw) {
        String text = raw.strip();
        if (estTokens(text) <= ASSISTANT_CAP_TOKENS) return text;
        int budget = ASSISTANT_CAP_TOKENS * CHARS_PER_TOKEN;
        int splitIdx = text.offsetByCodePoints(0, budget);
        String head = text.substring(0, splitIdx).stripTrailing();
        int dropped = estTokens(text.substring(splitIdx));
        return head + " …[+" + dropped + " tok]";
    }

    /** One prior turn rendered to its block: user verbatim, assistant capped, tool as a terse marker. */
    private static String block(Turn turn) {
        return switch (turn.speaker()) {
            case "user" -> USER_PREFIX + turn.text().strip();
            case "assistant" -> ASSISTANT_PREFIX + capAssistant(turn.text());
            case "tool" -> ContentExtractor.toolMarker(turn.text(), turn.toolError());
            default ->
                throw new IllegalArgumentException("contract v2 knows user/assistant/tool, not " + turn.speaker());
        };
    }

    /** The scored trailing block: tool marker, bare (user + no context), else role-prefixed, never capped. */
    private static String finalBlock(Turn finalTurn, boolean hasContext) {
        if ("tool".equals(finalTurn.speaker())) {
            return ContentExtractor.toolMarker(finalTurn.text(), finalTurn.toolError());
        }
        String text = finalTurn.text().strip();
        if (!hasContext && "user".equals(finalTurn.speaker())) return text;
        return prefix(finalTurn.speaker()) + text;
    }

    /** Render prior turns per contract v2 (assistant capped). Returns {@code ""} for no context. */
    public static String renderContext(List<Turn> turns) {
        StringBuilder sb = new StringBuilder();
        for (Turn turn : turns) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(block(turn));
        }
        return sb.toString();
    }

    /**
     * The full model input: rendered prior turns + the scored USER message. With empty {@code context}
     * this degrades to the bare stripped final message (incumbent single-turn parity).
     */
    public static String renderInput(String context, String finalText) {
        return renderWithFinal(context, new Turn("user", finalText));
    }

    /**
     * General render: {@code context} (already-rendered prior turns) followed by a trailing scored
     * turn. A bare user turn with empty context degrades to the stripped message; every other case
     * emits the role-prefixed final block. The scored turn is never capped (it rides through whole;
     * the serving tokenizer truncates a single over-budget message). Blank final text appends nothing.
     */
    public static String renderWithFinal(String context, Turn finalTurn) {
        String finalText = finalTurn.text().strip();
        if (finalText.isEmpty()) return context;
        if (context.isEmpty()) {
            return "user".equals(finalTurn.speaker()) ? finalText : prefix(finalTurn.speaker()) + finalText;
        }
        return context + "\n" + prefix(finalTurn.speaker()) + finalText;
    }

    /** Convenience: {@link #renderInput}({@link #renderContext}(prior), finalUserText). */
    public static String render(List<Turn> priorTurns, String finalUserText) {
        return renderInput(renderContext(priorTurns), finalUserText);
    }

    /**
     * The tail of {@code context} starting at the {@code userTurns}-th-from-last USER turn — one
     * "exchange" per user turn, so the assistant reply and any tool markers that FOLLOW a kept user
     * turn ride along with it.
     *
     * <p>Anchored on user turns rather than on a raw block count because a block count cuts an
     * arbitrary distance into the past: one turn that fired six tools would consume the whole window
     * and evict the user message the reply is answering. {@code userTurns <= 0} returns an empty
     * context (the bare final turn); a window larger than the thread returns it unchanged.
     */
    public static List<Turn> windowByUserTurns(List<Turn> context, int userTurns) {
        if (userTurns <= 0) return List.of();
        int seen = 0;
        for (int i = context.size() - 1; i >= 0; i--) {
            if ("user".equals(context.get(i).speaker()) && ++seen == userTurns) {
                return List.copyOf(context.subList(i, context.size()));
            }
        }
        return List.copyOf(context);
    }

    /**
     * {@code context} with every assistant turn's prose replaced by {@link #ASSISTANT_STUB}, leaving
     * user turns and tool markers untouched. Turn COUNT and ORDER are preserved — the alternation is
     * the signal being kept.
     */
    public static List<Turn> stubAssistants(List<Turn> context) {
        List<Turn> out = new ArrayList<>(context.size());
        for (Turn t : context) {
            out.add("assistant".equals(t.speaker()) ? Turn.assistant(ASSISTANT_STUB) : t);
        }
        return out;
    }

    /**
     * Serialize a whole session thread within {@code budget} characters using trajectory-preserving
     * eviction. {@code context} is the oldest-first prior turns, {@code finalTurn} the scored trailing
     * turn (never dropped, never capped). Under budget the whole thread renders; over it, the baseline
     * head (earliest turn through the earliest failure marker) and the most-recent {@code recentK}
     * turns are kept, the redundant middle is thinned, and the drop is marked {@code [… N earlier turns
     * elided …]}. Mirrors Python {@code reduce_thread}; pinned by the {@code reduction} fixtures.
     */
    public static String reduceThread(List<Turn> context, Turn finalTurn, int budget, int recentK) {
        List<String> blocks = new ArrayList<>(context.size());
        for (Turn t : context) blocks.add(block(t));
        boolean hasContext = !blocks.isEmpty();
        String finalBlk = finalBlock(finalTurn, hasContext);

        String full = hasContext ? String.join("\n", blocks) + "\n" + finalBlk : finalBlk;
        if (full.length() <= budget || !hasContext) return full;

        int n = blocks.size();
        int headEnd = 1; // the earliest turn is always an anchor
        for (int i = 0; i < n; i++) {
            if (context.get(i).isErrorMarker()) {
                headEnd = Math.max(headEnd, i + 1); // keep through the earliest failure — the antecedent
                break;
            }
        }
        int tailStart = Math.max(headEnd, n - recentK);
        List<String> head = new ArrayList<>(blocks.subList(0, headEnd));
        List<String> tail = new ArrayList<>(blocks.subList(tailStart, n));
        int dropped = tailStart - headEnd;

        String result = assemble(head, tail, dropped, finalBlk);
        while (result.length() > budget && tail.size() > 1) {
            tail.remove(0); // thin the recent tail from its oldest side
            dropped++;
            result = assemble(head, tail, dropped, finalBlk);
        }
        return result;
    }

    private static String assemble(List<String> head, List<String> tail, int dropped, String finalBlk) {
        List<String> parts = new ArrayList<>(head);
        if (dropped > 0) parts.add(elision(dropped));
        parts.addAll(tail);
        parts.add(finalBlk);
        return String.join("\n", parts);
    }

    /**
     * Render a message's {@code parts} array to its turn text, non-text parts as typed placeholders /
     * tool markers (contract v2). Mirrors Python {@code render_parts}; delegates each part to {@link
     * ContentExtractor#partPlaceholder}, joining the non-empty tokens with a single space. Used both by
     * the golden-fixture parity test (normalized part shape) and, via {@link ContentExtractor}, by the
     * assembler on real gen_ai envelopes.
     */
    public static String renderParts(JsonNode parts) {
        StringBuilder sb = new StringBuilder();
        if (parts != null && parts.isArray()) {
            for (JsonNode part : parts) {
                String token = ContentExtractor.partPlaceholder(part);
                if (!token.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(token);
                }
            }
        }
        return sb.toString();
    }
}
