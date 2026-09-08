// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.detector;

import ai.tessary.evals.classifier.ClassifierField;
import ai.tessary.evals.classifier.catalog.BuiltInDetector;
import ai.tessary.evals.classifier.substrate.SubstrateObservation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A regex/keyword detector: the cheapest signal class. A natural-language phrase is compiled
 * <b>once</b>, at signal-definition time, into a {@link Pattern} via the {@link NlPhraseCompiler} seam,
 * and that pattern is matched literally over the observation's text (input, output, or both — per
 * {@link ClassifierField}) at evaluation time with <b>no per-trace model call</b>. Unlike the
 * encoder classifier (which scores the whole assembled conversation thread), a literal keyword match
 * is a per-observation, field-restricted search: "the OUTPUT contains X" must stay the output, not
 * leak into prior turns. Backs user-authored regex signals; the one-time NL→regex {@code compile} step
 * is the only work beyond {@link Matcher#find}.
 *
 * <p>The default phrases are compiled to patterns at construction. A classifier's per-project
 * {@code config_json} ({@code {"phrases":[...], "field":"OUTPUT", "word_boundary":true}}) overrides them;
 * those overrides are compiled lazily and memoized in a bounded cache (keyed by phrase + word-boundary),
 * so evaluation never recompiles per observation and never calls a model.
 */
public final class RegexDetector implements BuiltInDetector {

    private static final int MAX_CACHED_PATTERNS = 256;

    private final String kind;
    private final ClassifierField defaultField;
    private final String severity;
    private final boolean defaultWordBoundary;
    private final List<Pattern> defaultPatterns;
    private final NlPhraseCompiler compiler;
    private final ObjectMapper mapper;
    private final Map<String, Pattern> compiledCache;

    public RegexDetector(
            String kind,
            ClassifierField field,
            String severity,
            List<String> defaultPhrases,
            boolean wordBoundary,
            NlPhraseCompiler compiler,
            ObjectMapper mapper) {
        this.kind = kind;
        this.defaultField = field;
        this.severity = severity;
        this.defaultWordBoundary = wordBoundary;
        this.compiler = compiler;
        this.mapper = mapper;
        this.compiledCache = new ConcurrentHashMap<>();
        List<Pattern> compiled = new ArrayList<>(defaultPhrases.size());
        for (String phrase : defaultPhrases) {
            if (!phrase.isBlank()) compiled.add(compiler.compile(phrase, wordBoundary));
        }
        this.defaultPatterns = List.copyOf(compiled);
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public Detection detect(SubstrateObservation obs, @Nullable String config) {
        ConfigShape shape = parse(config);
        ClassifierField field = field(shape);
        String haystack = fieldText(obs, field);
        if (haystack.isBlank()) return Detection.none();
        for (Pattern pattern : patterns(shape)) {
            Matcher matcher = pattern.matcher(haystack);
            if (matcher.find()) {
                return Detection.fired(severity, evidence(pattern, matcher.group(), field));
            }
        }
        return Detection.none();
    }

    private List<Pattern> patterns(@Nullable ConfigShape shape) {
        if (shape == null) return defaultPatterns;
        List<String> phrases = shape.phrases();
        if (phrases == null || phrases.isEmpty()) return defaultPatterns;
        Boolean wb = shape.wordBoundary();
        boolean wordBoundary = wb == null ? defaultWordBoundary : wb;
        List<Pattern> overrides = new ArrayList<>(phrases.size());
        for (String phrase : phrases) {
            if (!phrase.isBlank()) overrides.add(compiled(phrase, wordBoundary));
        }
        return overrides.isEmpty() ? defaultPatterns : overrides;
    }

    private Pattern compiled(String phrase, boolean wordBoundary) {
        String key = wordBoundary + " " + phrase;
        Pattern cached = compiledCache.get(key);
        if (cached != null) return cached;
        if (compiledCache.size() >= MAX_CACHED_PATTERNS) compiledCache.clear();
        return compiledCache.computeIfAbsent(key, k -> compiler.compile(phrase, wordBoundary));
    }

    /** The field-restricted clean text of one observation: user input, agent output, or both. */
    private static String fieldText(SubstrateObservation obs, ClassifierField field) {
        return switch (field) {
            case INPUT -> obs.inputText();
            case OUTPUT -> obs.outputText();
            case BOTH -> obs.inputText() + "\n" + obs.outputText();
        };
    }

    private ClassifierField field(@Nullable ConfigShape shape) {
        if (shape == null) return defaultField;
        String field = shape.field();
        if (field == null || field.isBlank()) return defaultField;
        try {
            return ClassifierField.valueOf(field.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultField;
        }
    }

    @Nullable
    private ConfigShape parse(@Nullable String config) {
        if (config == null || config.isBlank()) return null;
        try {
            return mapper.readValue(config, ConfigShape.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String evidence(Pattern pattern, String matched, ClassifierField field) {
        String span = matched.length() > 200 ? matched.substring(0, 200) : matched;
        try {
            return mapper.writeValueAsString(Map.of(
                    "matched", span,
                    "pattern", pattern.pattern(),
                    "field", field.name().toLowerCase(Locale.ROOT)));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * The keys this detector owns. {@code ignoreUnknown} is load-bearing, not politeness: a classifier's
     * {@code config_json} is shared with features that key off the same blob (the pre-deploy loop
     * reads {@code surfaces} from it), and the platform mapper is a bare {@code new ObjectMapper()}
     * with {@code FAIL_ON_UNKNOWN_PROPERTIES} left ON. Without this, one foreign key made {@link
     * #parse} throw, the catch returned null, and the detector fell back to its EMPTY default
     * patterns — so the signal silently matched nothing, with no error anywhere.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigShape(
            @Nullable List<String> phrases,
            @Nullable String field,

            @com.fasterxml.jackson.annotation.JsonProperty("word_boundary") @Nullable
            Boolean wordBoundary) {}
}
