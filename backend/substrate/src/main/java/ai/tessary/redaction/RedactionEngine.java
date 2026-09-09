// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Pure regex redaction: applies a project's compiled {@link CompiledRule} set to a string,
 * replacing every match with the rule's replacement. Used on the substrate write path
 * ({@code RedactionService} → {@code SubstrateWriter}) to strip PII <em>before</em> persistence, and by the
 * playground test endpoint to preview a rule against sample text.
 *
 * <p><b>Redaction is a deliberate, declared transform — never a silent truncation.</b> The project rule
 * forbids silently clipping telemetry content; redaction is the opposite of clipping: it is an explicit,
 * project-configured substitution that the operator authors and tests in the playground. A rule whose
 * regex fails to compile is skipped at compile time (never applied, never throws on the hot path), so one
 * malformed rule can never fail a write.
 *
 * <p>Stateless and thread-safe: a {@link CompiledRule} holds an immutable compiled {@link Pattern}, and
 * {@link #apply} allocates only on a match (early-returns the input unchanged when no rule matches), so the
 * common no-PII string costs one {@code find()} scan per rule and zero allocation.
 */
public final class RedactionEngine {

    private RedactionEngine() {}

    /** A redaction rule with its regex already compiled (validated once, applied many times). */
    public record CompiledRule(String name, Pattern pattern, String replacement) {}

    /**
     * Compile a rule's regex, or return null when it does not compile (a malformed rule is skipped, never
     * fatal). The replacement is treated as a literal string (no {@code $group} / backslash interpretation),
     * so a replacement like {@code [REDACTED]} or {@code $} is substituted verbatim.
     */
    public static @Nullable CompiledRule compile(String name, String regex, String replacement) {
        try {
            return new CompiledRule(name, Pattern.compile(regex), replacement);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Validate a regex compiles, surfacing the failure (used by the playground/CRUD to reject bad input). */
    public static boolean isValidRegex(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * Apply every rule, in order, to {@code text}. Returns {@code text} unchanged (same reference) when it
     * is null/blank or no rule matches — the hot-path common case allocates nothing. Each rule's
     * replacement is substituted literally.
     */
    public static @Nullable String apply(@Nullable String text, List<CompiledRule> rules) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return text;
        String out = text;
        for (CompiledRule rule : rules) {
            // replaceAll() alone, with no find() pre-check. The old form ran the regex TWICE on
            // every matching rule: find() scans, then replaceAll() resets the matcher and scans
            // again from the start. replaceAll() already returns the input unchanged when there is
            // no match, so the pre-check bought nothing and doubled the cost of the one thing on
            // this path that is expensive. It returns the same reference when nothing matched, so
            // the allocate-nothing property the javadoc promises still holds.
            out = rule.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(rule.replacement()));
        }
        return out;
    }

    /**
     * A run of inline binary payload — standard base64, as carried by a {@code data:} URI or a
     * provider content block. {@link RawEntry} stores media INLINE, so a single ingested entry
     * routinely carries hundreds of KB to megabytes of this.
     */
    /** Minimum contiguous payload characters before a run is treated as binary. */
    private static final int MIN_RUN = 512;

    /**
     * Payload alphabet, tested by index rather than by regex. {@code Pattern("[...]{512,}")} looked
     * equivalent and was not: {@code {512,}} re-scans up to run-length at EVERY start position, so on
     * any payload that never forms a 512-contiguous run the matcher does O(n x 512) work and then the
     * ordinary redaction runs anyway. Measured at 1 MB, that made this method SLOWER than no
     * optimisation at all on the shapes that matter — MIME-wrapped base64 (line breaks every 76
     * chars, the default of most encoders) 264 -> 486 ms, base64url 556 -> 687 ms, 500-char opaque
     * tokens 508 -> 1473 ms — on the exact path this exists to de-saturate. A single left-to-right
     * pass is O(n) unconditionally and cannot regress any input.
     *
     * <p>Base64url's {@code -}/{@code _} are deliberately absent: a long run of digits and dashes
     * would otherwise qualify as binary and could hide an embedded SSN.
     */
    private static boolean isPayloadChar(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '+'
                || c == '/'
                || c == '=';
    }

    /**
     * Characters re-examined at each end of a skipped run. A greedy run swallows any adjacent
     * alphabet characters, including the tail of real text — {@code jane@example.com} followed by a
     * blob puts {@code com} INSIDE the run, leaving {@code jane@example.} as the text segment, which
     * no longer matches the email rule. Redacting a margin back into the text side closes that: no
     * built-in pattern can match more than ~320 characters (RFC 5321 caps local-part at 64 and domain
     * at 255), so 512 covers every one of them with room to spare, while still skipping the interior
     * of a megabyte payload. Cost is ~1 KB scanned per blob instead of ~1 MB.
     */
    private static final int EDGE_MARGIN = 512;

    /**
     * Apply every rule to the human-readable parts of {@code text}, leaving inline binary payload
     * untouched.
     *
     * <p><b>Why this exists.</b> Redaction over base64 was the dominant cost on the ingest path and it
     * bought nothing. Measured on one ~1 MB inline image: 314 ms for the five built-ins, 281 ms of it
     * the email rule alone — because base64's alphabet is {@code [A-Za-z0-9+/=]}, exactly what
     * {@code [A-Za-z0-9._%+-]{1,64}} walks character by character. A batch of twenty such entries cost
     * 6.3 s of CPU, synchronously, and that is what saturated production on 2026-07-31.
     *
     * <p>It bought little: standard base64's alphabet is {@code [A-Za-z0-9+/=]}, so an email (needs
     * {@code @}) or an IPv4 (needs {@code .}) cannot match inside a run. SSN/phone/card are not
     * excluded by the alphabet — digits are in it — but their {@code \b} anchors and separator
     * requirements make a match inside a contiguous 512-char alphanumeric run vanishingly unlikely.
     * The card rule is the one that CAN match, because its separator class is optional: a chance run
     * of 13-16 digits inside an image matches, so the old behaviour could silently corrupt media
     * bytes by substituting {@code [REDACTED_CARD]} into them. Skipping binary fixes that too.
     *
     * <p>Note the alphabet deliberately EXCLUDES base64url's {@code -} and {@code _}. Including them
     * would let a long run of digits-and-dashes qualify as "binary" and skip an embedded SSN. A
     * base64url payload therefore still takes the slow path — correct but unoptimised, which is the
     * right way round for a security control.
     *
     * <p><b>Segment, don't skip wholesale.</b> A multimodal message interleaves text and media in one
     * field, so skipping the whole field on sight of a blob would let real PII in the text ride through.
     * Instead the binary runs are excised, the text between them is redacted normally, and the runs are
     * re-joined byte-identical.
     */
    public static @Nullable String applyToTextParts(@Nullable String text, List<CompiledRule> rules) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return text;

        StringBuilder out = null; // allocated lazily: most fields contain no payload at all
        int cursor = 0;
        int i = 0;
        boolean changed = false;
        final int n = text.length();
        while (i < n) {
            if (!isPayloadChar(text.charAt(i))) {
                i++;
                continue;
            }
            int runStart = i;
            while (i < n && isPayloadChar(text.charAt(i))) i++; // single pass, never re-scans
            int skipFrom = runStart + EDGE_MARGIN;
            int skipTo = i - EDGE_MARGIN;
            if (skipTo - skipFrom < MIN_RUN) continue; // too short to be worth skipping — redact it as text
            if (out == null) out = new StringBuilder(n);
            String segment = text.substring(cursor, skipFrom);
            // apply() is null-in/null-out and `segment` is a substring, so this is never null.
            String redacted = Objects.requireNonNull(apply(segment, rules));
            changed |= !redacted.equals(segment);
            out.append(redacted);
            out.append(text, skipFrom, skipTo); // payload interior preserved verbatim
            cursor = skipTo;
        }
        if (out == null) return apply(text, rules); // no payload: the ordinary path, unchanged semantics

        String tail = text.substring(cursor);
        String redactedTail = Objects.requireNonNull(apply(tail, rules));
        changed |= !redactedTail.equals(tail);
        out.append(redactedTail);
        // Nothing matched anywhere, so the rebuilt string is byte-identical to the input. Return the
        // ORIGINAL reference and let the copy be garbage: media fields run to megabytes and the
        // no-PII case is the overwhelming majority.
        return changed ? out.toString() : text;
    }

    /**
     * Strict reader for the structural path. {@code FAIL_ON_TRAILING_TOKENS} matches the standing
     * convention for deciding what counts as JSON here ({@code Jsonb.orNull} rejects a value followed by
     * prose for the same reason): a tool that prints an object and then keeps talking has not sent JSON,
     * and treating its first object as the whole payload would redact the object and leave the prose
     * beside it untouched.
     */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Depth ceiling for the structural walk, counting nested-JSON-in-a-string hops as levels of their own.
     * Real message envelopes are a handful deep; this only stops a pathological payload from turning the
     * drain thread's stack into the failure.
     */
    private static final int MAX_JSON_DEPTH = 64;

    /**
     * Above this length the payload is redacted as text without attempting a parse. This runs on the
     * drain thread for every entry, and materializing the whole tree of a multi-megabyte inline-media
     * payload to find its string leaves costs more than the leaves are worth — {@link #applyToTextParts}
     * already handles that shape well, by skipping the binary interior entirely.
     */
    private static final int MAX_JSON_PARSE_CHARS = 2_000_000;

    /**
     * Apply every rule to the string values of a JSON document, leaving its structure and its non-string
     * values untouched. Falls back to {@link #applyToTextParts} for anything that is not a JSON object or
     * array, so a payload of prose is still redacted exactly as before.
     *
     * <p><b>Why this exists — regex over serialized JSON is not safe and cannot be made safe.</b> A rule
     * substitutes a bare token, and JSON's grammar says whether a bare token is legal where it lands. In a
     * string it is fine; in a number position it is not. A Chrome {@code tabId} is a ten-digit integer and
     * the built-in phone rule matches ten digits, so {@code "tabId": 1234567890} became
     * {@code "tabId": [REDACTED_PHONE]} — not JSON — on <b>48.9% of MCP tool calls</b> in one corpus, and
     * the typed {@code tool_call.arguments} column went null for every one of them. The content survived
     * in {@code arguments_raw}; what was lost was the ability to query it.
     *
     * <p><b>This is the third patch of one root cause, and the first structural one.</b> The card rule
     * once ate {@code 0.7799999999999999} and was fixed with lookarounds;
     * {@code RedactionService.redactMetadata} exempts usage and cost keys by name because a cost "matches
     * a phone-number rule on digit count alone". Each of those bought one pattern against one numeric
     * shape. Walking the parsed tree ends the category instead: a number is unreachable by a text rule
     * because it is never handed to one.
     *
     * <p><b>Prior art is unanimous.</b> Langfuse's SDK masks the Python object and only then serializes,
     * so its own phone pattern never sees an {@code int}. The OpenTelemetry Collector's redaction
     * processor does not scan non-string attribute values at all by default, and OTTL's masking functions
     * skip them. Presidio dispatches per field rather than over a whole serialized document.
     *
     * <p><b>The trap this method exists to survive.</b> In the OpenAI-native tool shape, {@code arguments}
     * is a JSON <em>string containing JSON</em> — {@code {"arguments": "{\"tabId\": 1234567890}"}} — and
     * {@code SpanSideTables.toolPartField} reads it out with {@code isValueNode() ? asText() : toString()}.
     * A walk that treated that leaf as ordinary text would regex the nested document and reproduce this
     * bug exactly. So a string leaf that itself parses as an object or array is recursed into and
     * re-serialized, bounded by {@link #MAX_JSON_DEPTH}.
     *
     * <p>Unchanged input returns the <em>same reference</em>, never a re-serialized equivalent. That is
     * load-bearing beyond allocation: {@code SpanBatchWriter} strips an attribute only when its value is
     * byte-for-byte identical to the promoted column, and a reformat on one side of that comparison and
     * not the other would silently stop the strip working.
     */
    public static @Nullable String applyToJson(@Nullable String json, List<CompiledRule> rules) {
        if (json == null || json.isEmpty() || rules.isEmpty()) return json;
        if (json.length() > MAX_JSON_PARSE_CHARS) return applyToTextParts(json, rules);
        JsonNode root = parseContainer(json);
        if (root == null) return applyToTextParts(json, rules);
        JsonNode redacted = redactNode(root, rules, 0);
        if (redacted == null) return json; // nothing matched: original bytes, original reference
        try {
            return JSON.writeValueAsString(redacted);
        } catch (JsonProcessingException e) {
            // It parsed, so it serializes; this is unreachable in practice. Falling back to the text path
            // keeps the guarantee that matters — nothing unredacted is ever returned from here.
            return applyToTextParts(json, rules);
        }
    }

    /** The document as a container node, or null when it is not one (prose, a bare scalar, malformed). */
    private static @Nullable JsonNode parseContainer(String text) {
        // Cheap reject on the first non-space character, before paying for a parse: the overwhelming
        // majority of fields on this path are prose, and a JSON container can only start one way.
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i == text.length()) return null;
        char first = text.charAt(i);
        if (first != '{' && first != '[') return null;
        try {
            JsonNode node = JSON.readTree(text);
            return node != null && (node.isObject() || node.isArray()) ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Rewrite one node, returning null when nothing beneath it changed. Null-means-unchanged rather than
     * a flag: it lets an untouched subtree be shared instead of copied, so the common no-PII document
     * allocates nothing at all, matching {@link #apply}'s contract.
     */
    private static @Nullable JsonNode redactNode(JsonNode node, List<CompiledRule> rules, int depth) {
        if (depth >= MAX_JSON_DEPTH) return null;
        if (node.isObject()) {
            ObjectNode copy = null;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode rewritten = redactNode(field.getValue(), rules, depth + 1);
                if (rewritten != null) {
                    if (copy == null) copy = node.deepCopy();
                    copy.set(field.getKey(), rewritten);
                }
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = null;
            for (int i = 0; i < node.size(); i++) {
                JsonNode rewritten = redactNode(node.get(i), rules, depth + 1);
                if (rewritten != null) {
                    if (copy == null) copy = node.deepCopy();
                    copy.set(i, rewritten);
                }
            }
            return copy;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            String redacted = redactStringLeaf(text, rules, depth);
            return redacted.equals(text) ? null : TextNode.valueOf(redacted);
        }
        // Numbers, booleans and nulls are returned untouched. This is the whole point: it is not that a
        // rule is unlikely to match them, it is that no rule is ever offered them.
        return null;
    }

    /** A string leaf: recursed into when it is itself a JSON container, redacted as text otherwise. */
    private static String redactStringLeaf(String text, List<CompiledRule> rules, int depth) {
        if (depth + 1 < MAX_JSON_DEPTH && text.length() <= MAX_JSON_PARSE_CHARS) {
            JsonNode nested = parseContainer(text);
            if (nested != null) {
                JsonNode rewritten = redactNode(nested, rules, depth + 1);
                if (rewritten == null) return text;
                try {
                    return JSON.writeValueAsString(rewritten);
                } catch (JsonProcessingException e) {
                    return Objects.requireNonNull(applyToTextParts(text, rules));
                }
            }
        }
        return Objects.requireNonNull(applyToTextParts(text, rules));
    }

    /** Count how many matches a single compiled rule has in {@code text} (for the playground preview). */
    public static int countMatches(@Nullable String text, CompiledRule rule) {
        if (text == null || text.isEmpty()) return 0;
        Matcher m = rule.pattern().matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** Compile a list of {@link RedactionRuleRow}s, dropping any whose regex does not compile. */
    public static List<CompiledRule> compileAll(List<RedactionRuleRow> rows) {
        List<CompiledRule> compiled = new ArrayList<>(rows.size());
        for (RedactionRuleRow row : rows) {
            CompiledRule rule = compile(row.name(), row.pattern(), row.replacement());
            if (rule != null) compiled.add(rule);
        }
        return compiled;
    }
}
