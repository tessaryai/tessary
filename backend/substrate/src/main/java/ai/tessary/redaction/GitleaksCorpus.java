// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import ai.tessary.open.coverage.ExcludeFromJacocoGeneratedReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;

/**
 * Vendored by {@code scripts/sync-gitleaks.sh} at a pinned release; a bump is a reviewed diff. Matching follows
 * gitleaks. Redaction does not always: a rule with several capture groups (e.g. {@code jwt-base64}) replaces its whole
 * match, since its "secret" is a fragment. {@link Finding#anchored} marks rules anchored on a provider-stamped literal
 * rather than vendor-name context.
 */
public final class GitleaksCorpus {

    static final String RESOURCE = "redaction/gitleaks-rules.json";

    public static final String PATTERN_SCHEME = "gitleaks:";

    private static final Pattern KEYWORD_CONTEXT = Pattern.compile("^\\(\\?i\\)\\[\\\\w\\.-\\]\\{0,50\\}\\?\\(\\?:");

    private static final Pattern POSIX_CLASS =
            Pattern.compile("\\[:(alnum|alpha|digit|lower|upper|space|punct|xdigit|word|blank|cntrl|graph|print):]");

    private static final String GENERIC_RULE_PREFIX = "generic";

    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(?:,\\d*)?}");

    public record Finding(String ruleId, boolean anchored, int start, int end) {}

    private enum Target {
        SECRET,
        MATCH,
        LINE
    }

    private record Allowlist(List<Pattern> regexes, Target target, List<String> stopwords, boolean requireAll) {}

    private record Rule(
            String id,
            Pattern pattern,
            @Nullable Pattern identifier,
            int captureGroups,
            int secretGroup,
            @Nullable Double entropy,
            boolean anchored,
            List<Allowlist> allowlists) {}

    private static final int CONTEXT_PREFIX = 50;

    /** Twenty name characters plus quotes or spaces before the operator, with slack for a longer name alternative. */
    private static final int ASSIGNMENT_REACH = 88;

    private static final class Holder {
        private static final GitleaksCorpus INSTANCE = loadResource();
    }

    private final String version;
    private final List<Rule> rules;
    private final KeywordIndex keywords;
    private final @Nullable Allowlist global;

    private GitleaksCorpus(String version, List<Rule> rules, KeywordIndex keywords, @Nullable Allowlist global) {
        this.version = version;
        this.rules = rules;
        this.keywords = keywords;
        this.global = global;
    }

    public static GitleaksCorpus get() {
        return Holder.INSTANCE;
    }

    public String patternValue() {
        return PATTERN_SCHEME + version;
    }

    /**
     * On overlap: earlier start, then anchored, then longer, then anything over {@code generic-api-key}, since the
     * specific name is what a reader acts on.
     */
    public List<Finding> find(String text) {
        if (text.isEmpty()) return List.of();
        BitSet candidates = keywords.candidates(text);
        List<Finding> found = new ArrayList<>();
        for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
            Rule rule = rules.get(i);
            Pattern identifier = rule.identifier();
            if (identifier != null) {
                findInContext(rule, identifier, text, found);
            } else {
                Matcher m = rule.pattern().matcher(text);
                while (m.find()) accept(rule, text, m, found);
            }
        }
        if (found.size() < 2) return found;
        found.sort(Comparator.comparingInt(Finding::start)
                .thenComparing(f -> !f.anchored())
                .thenComparing(f -> f.start() - f.end())
                .thenComparing(f -> f.ruleId().startsWith(GENERIC_RULE_PREFIX)));
        List<Finding> kept = new ArrayList<>(found.size());
        int reach = -1;
        for (Finding f : found) {
            if (f.start() < reach) continue;
            kept.add(f);
            reach = f.end();
        }
        return kept;
    }

    /**
     * Performance: Java's backtracking tries the template's lazy {@code [\w.-]{0,50}?} prefix at every position, which
     * made {@code generic-api-key} most of the corpus's cost. Find vendor names first, skip any without an operator
     * after, and anchor the full rule once; the result is the same leftmost match.
     */
    private void findInContext(Rule rule, Pattern identifier, String text, List<Finding> found) {
        Matcher m = rule.pattern().matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
        Matcher names = identifier.matcher(text);
        int reach = 0;
        while (names.find()) {
            int name = names.start();
            if (name < reach || !assignmentFollows(text, names.end())) continue;
            int from = name;
            while (from > 0 && name - from < CONTEXT_PREFIX && isNameChar(text.charAt(from - 1))) from--;
            m.region(from, text.length());
            if (m.lookingAt() && accept(rule, text, m, found)) reach = m.end();
        }
    }

    private boolean accept(Rule rule, String text, Matcher m, List<Finding> found) {
        if (m.end() == m.start()) return false;
        String secret = secretOf(m, rule);
        Double floor = rule.entropy();
        if (floor != null && entropy(secret) < floor) return false;
        if (allowed(global, text, m, secret)) return false;
        for (Allowlist list : rule.allowlists()) {
            if (allowed(list, text, m, secret)) return false;
        }
        int[] span = replacedSpan(m, rule);
        found.add(new Finding(rule.id(), rule.anchored(), span[0], span[1]));
        return true;
    }

    private static boolean assignmentFollows(String text, int end) {
        int limit = Math.min(text.length(), end + ASSIGNMENT_REACH);
        for (int i = end; i < limit; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '=', '>', ':', '|', '?', ',' -> {
                    return true;
                }
                case ' ', '\t', '\n', '\r', '\f', '\u000B', '\'', '"' -> {}
                default -> {
                    if (!isNameChar(c)) return false;
                }
            }
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '.'
                || c == '-';
    }

    private static String secretOf(Matcher m, Rule rule) {
        if (rule.secretGroup() > 0 && rule.secretGroup() <= rule.captureGroups()) {
            String g = m.group(rule.secretGroup());
            return g == null ? "" : g;
        }
        for (int g = 1; g <= rule.captureGroups(); g++) {
            String s = m.group(g);
            if (s != null && !s.isEmpty()) return s;
        }
        return m.group();
    }

    private static int[] replacedSpan(Matcher m, Rule rule) {
        int group = rule.secretGroup() > 0 ? rule.secretGroup() : rule.captureGroups() == 1 ? 1 : 0;
        if (group > 0 && group <= rule.captureGroups() && m.start(group) >= 0 && m.end(group) > m.start(group)) {
            return new int[] {m.start(group), m.end(group)};
        }
        return new int[] {m.start(), m.end()};
    }

    private static boolean allowed(@Nullable Allowlist list, String text, Matcher m, String secret) {
        if (list == null) return false;
        String target =
                switch (list.target()) {
                    case SECRET -> secret;
                    case MATCH -> m.group();
                    case LINE -> lineAround(text, m.start(), m.end());
                };
        boolean regexHit =
                list.regexes().stream().anyMatch(p -> p.matcher(target).find());
        boolean stopwordHit = false;
        if (!list.stopwords().isEmpty()) {
            String lower = secret.toLowerCase(Locale.ROOT);
            stopwordHit = list.stopwords().stream().anyMatch(lower::contains);
        }
        if (list.requireAll()) {
            return (list.regexes().isEmpty() || regexHit) && (list.stopwords().isEmpty() || stopwordHit);
        }
        return regexHit || stopwordHit;
    }

    private static String lineAround(String text, int start, int end) {
        int from = text.lastIndexOf('\n', start - 1) + 1;
        int to = text.indexOf('\n', end);
        return text.substring(from, to < 0 ? text.length() : to);
    }

    static double entropy(String s) {
        if (s.isEmpty()) return 0;
        java.util.Map<Character, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < s.length(); i++) counts.merge(s.charAt(i), 1, Integer::sum);
        double bits = 0;
        for (int n : counts.values()) {
            double p = (double) n / s.length();
            bits -= p * (Math.log(p) / Math.log(2));
        }
        return bits;
    }

    /**
     * Named groups become plain groups (Java rejects underscores in group names, and numbering is kept); POSIX classes
     * are rewritten; a {@code {} that starts no repetition is escaped, since Java refuses it.
     */
    static String toJava(String re2) {
        StringBuilder out = new StringBuilder(re2.length() + 8);
        boolean inClass = false;
        for (int i = 0; i < re2.length(); i++) {
            char c = re2.charAt(i);
            if (c == '\\' && i + 1 < re2.length()) {
                out.append(c).append(re2.charAt(++i));
                continue;
            }
            if (inClass) {
                if (c == '[') {
                    Matcher posix = POSIX_CLASS.matcher(re2).region(i, re2.length());
                    if (posix.lookingAt()) {
                        out.append(javaClass(posix.group(1)));
                        i = posix.end() - 1;
                        continue;
                    }
                }
                if (c == ']') inClass = false;
                out.append(c);
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                if (i + 1 < re2.length() && re2.charAt(i + 1) == '^') out.append(re2.charAt(++i));
                if (i + 1 < re2.length() && re2.charAt(i + 1) == ']') out.append(re2.charAt(++i));
                continue;
            }
            if (c == '(' && re2.startsWith("(?P<", i)) {
                i = re2.indexOf('>', i);
                out.append('(');
                continue;
            }
            if (c == '(' && re2.startsWith("(?<", i) && i + 3 < re2.length() && Character.isLetter(re2.charAt(i + 3))) {
                i = re2.indexOf('>', i);
                out.append('(');
                continue;
            }
            if (c == '{' && !QUANTIFIER.matcher(re2).region(i, re2.length()).lookingAt()) {
                out.append("\\{");
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String javaClass(String posix) {
        return switch (posix) {
            case "alnum" -> "\\p{Alnum}";
            case "alpha" -> "\\p{Alpha}";
            case "digit" -> "\\d";
            case "lower" -> "\\p{Lower}";
            case "upper" -> "\\p{Upper}";
            case "space" -> "\\s";
            case "punct" -> "\\p{Punct}";
            case "xdigit" -> "\\p{XDigit}";
            case "word" -> "\\w";
            case "blank" -> "\\p{Blank}";
            case "cntrl" -> "\\p{Cntrl}";
            case "graph" -> "\\p{Graph}";
            default -> "\\p{Print}";
        };
    }

    @ExcludeFromJacocoGeneratedReport(
            "the corpus is a resource packaged in this jar, so the IOException the compiler requires cannot fire")
    private static GitleaksCorpus loadResource() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return load(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("the vendored gitleaks corpus is missing or unreadable: " + RESOURCE, e);
        }
    }

    /** A rule whose regex will not compile is left out. */
    static GitleaksCorpus load(JsonNode document) {
        List<Rule> rules = new ArrayList<>();
        List<List<String>> keywordsByRule = new ArrayList<>();
        for (JsonNode node : document.path("rules")) {
            String id = node.path("id").asText();
            String regex = node.path("regex").asText();
            Pattern pattern;
            try {
                pattern = Pattern.compile(toJava(regex));
            } catch (PatternSyntaxException e) {
                continue;
            }
            List<Allowlist> allowlists = new ArrayList<>();
            for (JsonNode list : node.path("allowlists")) {
                Allowlist compiled = allowlist(list);
                if (compiled != null) allowlists.add(compiled);
            }
            JsonNode entropy = node.path("entropy");
            Matcher context = KEYWORD_CONTEXT.matcher(regex);
            boolean anchored = !context.lookingAt();
            rules.add(new Rule(
                    id,
                    pattern,
                    anchored ? null : identifierOf(regex, context.end()),
                    pattern.matcher("").groupCount(),
                    node.path("secret_group").asInt(0),
                    entropy.isNumber() ? entropy.asDouble() : null,
                    anchored,
                    List.copyOf(allowlists)));
            List<String> keywords = new ArrayList<>();
            for (JsonNode k : node.path("keywords")) keywords.add(k.asText());
            keywordsByRule.add(keywords);
        }
        return new GitleaksCorpus(
                document.path("version").asText(),
                List.copyOf(rules),
                new KeywordIndex(keywordsByRule),
                allowlist(document.path("allowlist")));
    }

    private static Pattern identifierOf(String regex, int open) {
        int depth = 1;
        int i = open;
        while (i < regex.length() && depth > 0) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            i++;
        }
        return Pattern.compile("(?i)(?:" + toJava(regex.substring(open, i - 1)) + ")");
    }

    /** Null allows nothing; the rule it guards still runs. */
    private static @Nullable Pattern compileOrNull(String re2) {
        try {
            return Pattern.compile(toJava(re2));
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static @Nullable Allowlist allowlist(JsonNode node) {
        if (node.isMissingNode()) return null;
        List<Pattern> regexes = new ArrayList<>();
        for (JsonNode r : node.path("regexes")) {
            Pattern compiled = compileOrNull(r.asText());
            if (compiled != null) regexes.add(compiled);
        }
        List<String> stopwords = new ArrayList<>();
        for (JsonNode s : node.path("stopwords")) stopwords.add(s.asText().toLowerCase(Locale.ROOT));
        if (regexes.isEmpty() && stopwords.isEmpty()) return null;
        return new Allowlist(
                List.copyOf(regexes),
                switch (node.path("regex_target").asText()) {
                    case "match" -> Target.MATCH;
                    case "line" -> Target.LINE;
                    default -> Target.SECRET;
                },
                List.copyOf(stopwords),
                "AND".equalsIgnoreCase(node.path("condition").asText()));
    }
}
