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
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Pure regex redaction for the write path and the playground. A rule whose regex fails to compile is skipped, so one
 * malformed rule can never fail a write. Thread-safe, and {@link #apply} allocates only on a match.
 */
public final class RedactionEngine {

    private RedactionEngine() {}

    public sealed interface CompiledRule permits RegexRule, CorpusRule {
        String name();

        String replacement();
    }

    /** Replacement substituted literally. */
    public record RegexRule(String name, Pattern pattern, String replacement) implements CompiledRule {}

    /**
     * The one rule kind that reports what it matched: a credential redacted on the way in is what a leak detector reads
     * afterwards.
     */
    public record CorpusRule(String name, GitleaksCorpus corpus, String replacement) implements CompiledRule {}

    /** Null when the regex does not compile. The replacement is literal, so {@code $} is substituted verbatim. */
    public static @Nullable CompiledRule compile(String name, String regex, String replacement) {
        try {
            return new RegexRule(name, Pattern.compile(regex), replacement);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    public static boolean isValidRegex(String regex) {
        try {
            Pattern.compile(regex);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /** Returns the same reference when nothing matches. Only {@link CorpusRule} reports to {@code matched}. */
    public static @Nullable String apply(
            @Nullable String text,
            List<CompiledRule> rules,
            @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return text;
        String out = text;
        for (CompiledRule rule : rules) {
            out = switch (rule) {
                // No find() pre-check, which would scan twice; replaceAll() already returns the input when nothing
                // matches.
                case RegexRule r -> r.pattern().matcher(out).replaceAll(Matcher.quoteReplacement(r.replacement()));
                case CorpusRule c -> replaceFindings(out, c, matched);
            };
        }
        return out;
    }

    private static String replaceFindings(
            String text, CorpusRule rule, @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        List<GitleaksCorpus.Finding> findings = rule.corpus().find(text);
        if (findings.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (GitleaksCorpus.Finding f : findings) {
            out.append(text, cursor, f.start()).append(rule.replacement());
            cursor = f.end();
            if (matched != null) matched.accept(f, text.substring(f.start(), f.end()));
        }
        return out.append(text, cursor, text.length()).toString();
    }

    private static final int MIN_RUN = 512;

    /**
     * Tested by index, not regex: a quantified class re-scans at every start, which is quadratic. Base64url's
     * {@code -}/{@code _} are absent so a digit-and-dash run cannot hide an SSN.
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
     * A greedy run swallows adjacent text (`com` of an email), so a margin is redacted back on the text side; no
     * built-in pattern matches more than ~320 chars.
     */
    private static final int EDGE_MARGIN = 512;

    /**
     * Redact the readable parts of {@code text} and splice binary runs back byte-identical. Base64 cannot contain an
     * email or IPv4, but a digit run inside an image can match the card rule and corrupt media. Segmented rather than
     * skipped, so PII in interleaved text is still redacted.
     */
    public static @Nullable String applyToTextParts(@Nullable String text, List<CompiledRule> rules) {
        return applyToTextParts(text, rules, null);
    }

    public static @Nullable String applyToTextParts(
            @Nullable String text,
            List<CompiledRule> rules,
            @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (text == null || text.isEmpty() || rules.isEmpty()) return text;

        StringBuilder out = null;
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
            while (i < n && isPayloadChar(text.charAt(i))) i++;
            int skipFrom = runStart + EDGE_MARGIN;
            int skipTo = i - EDGE_MARGIN;
            if (skipTo - skipFrom < MIN_RUN) continue;
            if (out == null) out = new StringBuilder(n);
            String segment = text.substring(cursor, skipFrom);
            String redacted = Objects.requireNonNull(apply(segment, rules, matched));
            changed |= !redacted.equals(segment);
            out.append(redacted);
            out.append(text, skipFrom, skipTo);
            cursor = skipTo;
        }
        if (out == null) return apply(text, rules, matched);

        String tail = text.substring(cursor);
        String redactedTail = Objects.requireNonNull(apply(tail, rules, matched));
        changed |= !redactedTail.equals(tail);
        out.append(redactedTail);
        // Return the ORIGINAL reference: media fields run to megabytes.
        return changed ? out.toString() : text;
    }

    /**
     * {@code FAIL_ON_TRAILING_TOKENS}, as {@code Jsonb.orNull}: an object followed by prose is not JSON, and treating
     * it as such would leave the prose unredacted.
     */
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Counts nested-JSON-in-a-string hops; only stops a pathological payload from overflowing the drain thread's stack.
     */
    private static final int MAX_JSON_DEPTH = 64;

    /**
     * Larger payloads go straight to {@link #applyToTextParts}, which skips binary interiors far more cheaply than
     * parsing.
     */
    private static final int MAX_JSON_PARSE_CHARS = 2_000_000;

    /**
     * Redact the string values of a JSON document, never its structure: regex over serialized JSON turned a ten-digit
     * number into an unparsable token. A string leaf that parses as JSON is recursed into (OpenAI's {@code arguments}).
     * Unchanged input returns the same reference, which {@code SpanBatchWriter}'s byte-identity strip relies on.
     */
    public static @Nullable String applyToJson(
            @Nullable String json,
            List<CompiledRule> rules,
            @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (json == null || json.isEmpty() || rules.isEmpty()) return json;
        if (json.length() > MAX_JSON_PARSE_CHARS) return applyToTextParts(json, rules, matched);
        JsonNode root = parseContainer(json);
        if (root == null) return applyToTextParts(json, rules, matched);
        JsonNode redacted = redactNode(root, rules, 0, matched);
        if (redacted == null) return json;
        return redacted.toString();
    }

    private static @Nullable JsonNode parseContainer(String text) {
        // A JSON container can only start one way, and most fields are prose.
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

    /** Null-means-unchanged lets untouched subtrees be shared, so a clean document allocates nothing. */
    private static @Nullable JsonNode redactNode(
            JsonNode node,
            List<CompiledRule> rules,
            int depth,
            @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (depth >= MAX_JSON_DEPTH) return null;
        if (node.isObject()) {
            ObjectNode copy = null;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                JsonNode rewritten = redactNode(field.getValue(), rules, depth + 1, matched);
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
                JsonNode rewritten = redactNode(node.get(i), rules, depth + 1, matched);
                if (rewritten != null) {
                    if (copy == null) copy = node.deepCopy();
                    copy.set(i, rewritten);
                }
            }
            return copy;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            String redacted = redactStringLeaf(text, rules, depth, matched);
            return redacted.equals(text) ? null : TextNode.valueOf(redacted);
        }
        // Never offered to a rule at all; that is the whole point.
        return null;
    }

    private static String redactStringLeaf(
            String text,
            List<CompiledRule> rules,
            int depth,
            @Nullable BiConsumer<GitleaksCorpus.Finding, String> matched) {
        if (depth + 1 < MAX_JSON_DEPTH && text.length() <= MAX_JSON_PARSE_CHARS) {
            JsonNode nested = parseContainer(text);
            if (nested != null) {
                JsonNode rewritten = redactNode(nested, rules, depth + 1, matched);
                if (rewritten == null) return text;
                return rewritten.toString();
            }
        }
        return Objects.requireNonNull(applyToTextParts(text, rules, matched));
    }

    public static int countMatches(@Nullable String text, CompiledRule rule) {
        if (text == null || text.isEmpty()) return 0;
        return switch (rule) {
            case RegexRule r -> {
                Matcher m = r.pattern().matcher(text);
                int n = 0;
                while (m.find()) n++;
                yield n;
            }
            case CorpusRule c -> c.corpus().find(text).size();
        };
    }

    /**
     * A built-in row naming the corpus ({@code gitleaks:v8.30.1}) compiles to a {@link CorpusRule}; a custom rule
     * reading the same is an operator's literal.
     */
    public static List<CompiledRule> compileAll(List<RedactionRuleRow> rows) {
        List<CompiledRule> compiled = new ArrayList<>(rows.size());
        for (RedactionRuleRow row : rows) {
            if (row.builtIn() && row.pattern().startsWith(GitleaksCorpus.PATTERN_SCHEME)) {
                compiled.add(new CorpusRule(row.name(), GitleaksCorpus.get(), row.replacement()));
                continue;
            }
            CompiledRule rule = compile(row.name(), row.pattern(), row.replacement());
            if (rule != null) compiled.add(rule);
        }
        return compiled;
    }
}
