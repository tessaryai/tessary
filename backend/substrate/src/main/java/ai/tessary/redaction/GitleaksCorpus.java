// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

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
 * The gitleaks credential corpus, compiled for Java: what the platform treats as a leaked secret.
 *
 * <p><b>Why a corpus rather than our own patterns.</b> The hand-written credential rules were a dozen
 * vendors deep, and a leaked credential from the thirteenth was invisible to both redaction and detection.
 * gitleaks is the list the rest of the industry maintains as providers add formats. It is vendored as JSON
 * by {@code scripts/sync-gitleaks.sh} at a pinned release, with the licence beside it; a bump is a reviewed
 * diff to that one file.
 *
 * <p><b>Matching follows gitleaks.</b> A rule's regex runs only when one of its keywords appears (see
 * {@link KeywordIndex}); the secret is the rule's {@code secret_group}, else its first non-empty capture
 * group, else the whole match; a rule with an entropy floor drops a secret below it; and a secret the
 * global or the rule's own allowlist names is skipped.
 *
 * <p><b>Redaction does not always follow gitleaks.</b> gitleaks reports a secret, and for a rule with one
 * capture group that is the credential, so only that span is replaced and the key name beside it stays
 * readable. A rule with several groups is different: {@code jwt-base64} captures thirty-odd header
 * fragments, and its "secret" is the first ten characters of the token. Redacting that would leave the
 * token readable, so such a rule replaces its whole match.
 *
 * <p><b>Anchored is a fact about the rule's shape.</b> gitleaks writes two kinds of rule. One anchors the
 * credential on a literal the provider stamps into it ({@code AKIA}, {@code ghp_}, {@code xoxb-}), so a
 * match is the credential. The other finds a vendor's name near a random-looking string
 * ({@code (?i)[\w.-]{0,50}?(?:adobe)...}), which is only as good as the context. The first is
 * {@link Finding#anchored}; what that is worth to a detector is the detector's decision.
 */
public final class GitleaksCorpus {

    /** Where {@code scripts/sync-gitleaks.sh} writes the corpus. */
    static final String RESOURCE = "redaction/gitleaks-rules.json";

    /** How a built-in redaction rule names the corpus in place of a regex: {@code gitleaks:v8.30.1}. */
    public static final String PATTERN_SCHEME = "gitleaks:";

    /** gitleaks' keyword-context rule shape, the unanchored one; see the class javadoc. */
    private static final Pattern KEYWORD_CONTEXT = Pattern.compile("^\\(\\?i\\)\\[\\\\w\\.-\\]\\{0,50\\}\\?\\(\\?:");

    private static final Pattern POSIX_CLASS =
            Pattern.compile("\\[:(alnum|alpha|digit|lower|upper|space|punct|xdigit|word|blank|cntrl|graph|print):]");

    /** gitleaks' catch-all rules, which lose a tie to any rule that names the vendor. */
    private static final String GENERIC_RULE_PREFIX = "generic";

    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(?:,\\d*)?}");

    /** One credential found in a text. */
    public record Finding(String ruleId, boolean anchored, int start, int end) {}

    /** What an allowlist regex is tested against: the secret, the whole match, or the line it sits on. */
    private enum Target {
        SECRET,
        MATCH,
        LINE
    }

    private record Allowlist(List<Pattern> regexes, Target target, List<String> stopwords, boolean requireAll) {}

    /**
     * @param identifier for a keyword-context rule, just its vendor-name alternation; null for an anchored one
     */
    private record Rule(
            String id,
            Pattern pattern,
            @Nullable Pattern identifier,
            int captureGroups,
            int secretGroup,
            @Nullable Double entropy,
            boolean anchored,
            List<Allowlist> allowlists) {}

    /** How far before a vendor name a keyword-context match may start: the template's {@code [\w.-]{0,50}?}. */
    private static final int CONTEXT_PREFIX = 50;

    /**
     * How far past a vendor name to look for the assignment a keyword-context match needs. The template allows
     * twenty name characters and three quotes or spaces before the operator; the rest is slack for a longer
     * alternative of the name than the one the scan reported.
     */
    private static final int ASSIGNMENT_REACH = 88;

    private static final class Holder {
        private static final GitleaksCorpus INSTANCE = loadResource();
    }

    private final String version;
    private final List<Rule> rules;
    private final KeywordIndex keywords;
    private final @Nullable Allowlist global;
    private final List<String> uncompiled;

    private GitleaksCorpus(
            String version,
            List<Rule> rules,
            KeywordIndex keywords,
            @Nullable Allowlist global,
            List<String> uncompiled) {
        this.version = version;
        this.rules = rules;
        this.keywords = keywords;
        this.global = global;
        this.uncompiled = uncompiled;
    }

    /** The corpus on this classpath, loaded once. */
    public static GitleaksCorpus get() {
        return Holder.INSTANCE;
    }

    /** The gitleaks release the corpus was synced from. */
    public String version() {
        return version;
    }

    /** The pattern a built-in rule row carries to name this corpus. */
    public String patternValue() {
        return PATTERN_SCHEME + version;
    }

    /** Rules loaded and compiled. */
    public int size() {
        return rules.size();
    }

    /** Ids of rules whose regex would not compile in Java, and so never run. Empty on the pinned release. */
    public List<String> uncompiled() {
        return uncompiled;
    }

    /**
     * Every credential in {@code text}, ordered by position and never overlapping.
     *
     * <p>Where two rules claim overlapping text the earlier start wins, then the anchored rule, then the
     * longer span, then any rule over {@code generic-api-key}. An AWS key assigned to {@code api_key} is matched
     * by both {@code aws-access-token} and {@code generic-api-key} on the same characters, and a New Relic token
     * by {@code new-relic-browser-api-token} and {@code generic-api-key}; in both the specific name is the one
     * worth keeping, since it is what a reader acts on.
     */
    public List<Finding> find(String text) {
        return find(text, true);
    }

    /**
     * {@link #find} with every rule scanned over the whole text, keyword-context rules included. Slower by the
     * margin {@link #findInContext} exists to save, and the reference that fast path is checked against.
     */
    List<Finding> findExhaustive(String text) {
        return find(text, false);
    }

    private List<Finding> find(String text, boolean contextFastPath) {
        if (text.isEmpty()) return List.of();
        BitSet candidates = keywords.candidates(text);
        List<Finding> found = new ArrayList<>();
        for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
            Rule rule = rules.get(i);
            Pattern identifier = rule.identifier();
            if (contextFastPath && identifier != null) {
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
     * A keyword-context rule, tried once per place its vendor name appears rather than at every position.
     *
     * <p>This is a performance decision with a proof behind it, and it is the one that makes the corpus
     * affordable on the ingest path. gitleaks' template opens {@code (?i)[\w.-]{0,50}?(?:vendor)}, and Java's
     * backtracking engine tries that lazy prefix against every alternative at every position of the text:
     * {@code generic-api-key} alone was most of the corpus's cost on a keyword-dense megabyte. But a match can
     * only start in the run of name characters at most fifty long that ends at a vendor name, and it needs an
     * assignment operator shortly after. So the vendor names are found with the bare alternation, a name with no
     * operator after it is skipped, and the full rule is anchored once at the leftmost start it could have. The
     * match that returns is the leftmost one a scan of the whole text would have found there.
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

    /** Record {@code m} as a finding unless the rule's entropy floor or an allowlist rules it out. */
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

    /**
     * Whether an assignment operator can follow a vendor name ending at {@code end}: only name characters,
     * spaces and quotes may stand between, and an operator must arrive within {@link #ASSIGNMENT_REACH}.
     */
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

    /** {@code [\w.-]} in ASCII, the characters of the template's name prefix. */
    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '.'
                || c == '-';
    }

    /** gitleaks' secret: the named group, else the first non-empty group, else the match. */
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

    /** The span to replace: the secret for a rule that captures exactly it, else the whole match. */
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

    /** Shannon entropy in bits per character, the measure gitleaks' {@code entropy} floor is written in. */
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
     * Rewrite a Go RE2 regex into Java syntax, for the three constructs the two spell differently: a named
     * group ({@code (?P<name>} or {@code (?<name>}), which becomes a plain group because Java rejects the
     * underscores gitleaks puts in group names and a plain group keeps the same number; a POSIX class inside
     * brackets ({@code [[:alnum:]]}); and a {@code {} that starts no repetition, which RE2 reads as a
     * literal and Java refuses to compile.
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

    private static GitleaksCorpus loadResource() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return load(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("the vendored gitleaks corpus is missing or unreadable: " + RESOURCE, e);
        }
    }

    /** Compile a corpus document; a rule whose regex will not compile is recorded and left out. */
    static GitleaksCorpus load(JsonNode document) {
        List<Rule> rules = new ArrayList<>();
        List<List<String>> keywordsByRule = new ArrayList<>();
        List<String> uncompiled = new ArrayList<>();
        for (JsonNode node : document.path("rules")) {
            String id = node.path("id").asText();
            String regex = node.path("regex").asText();
            Pattern pattern;
            try {
                pattern = Pattern.compile(toJava(regex));
            } catch (PatternSyntaxException e) {
                uncompiled.add(id);
                continue;
            }
            List<Allowlist> allowlists = new ArrayList<>();
            for (JsonNode list : node.path("allowlists")) {
                Allowlist compiled = allowlist(list);
                if (compiled != null) allowlists.add(compiled);
            }
            JsonNode entropy = node.path("entropy");
            boolean anchored = !KEYWORD_CONTEXT.matcher(regex).lookingAt();
            rules.add(new Rule(
                    id,
                    pattern,
                    anchored ? null : identifierOf(regex),
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
                allowlist(document.path("allowlist")),
                List.copyOf(uncompiled));
    }

    /**
     * The vendor-name alternation of a keyword-context rule, case-insensitive and on its own: the group the
     * template opens after its name prefix, up to the paren that closes it.
     */
    private static Pattern identifierOf(String regex) {
        Matcher prefix = KEYWORD_CONTEXT.matcher(regex);
        if (!prefix.lookingAt()) throw new IllegalArgumentException("not a keyword-context rule: " + regex);
        int open = prefix.end();
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

    /** An allowlist regex in Java syntax, or null when it will not compile: it then allows nothing, and the rule it guards still runs. */
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
