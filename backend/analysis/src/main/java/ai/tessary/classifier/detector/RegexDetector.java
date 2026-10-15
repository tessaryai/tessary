// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
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
 * A regex/keyword detector with no per-trace model call. Phrases come only from {@code config_json}, compiled once via
 * {@link DeterministicNlPhraseCompiler} into a bounded memo cache, and matched within one field per
 * {@link ClassifierField}; a row without phrases matches nothing.
 */
public final class RegexDetector implements BuiltInDetector {

    private static final int MAX_CACHED_PATTERNS = 256;

    private final String kind;
    private final ClassifierField defaultField;
    private final String severity;
    private final boolean defaultWordBoundary;
    private final DeterministicNlPhraseCompiler compiler;
    private final ObjectMapper mapper;
    private final Map<String, Pattern> compiledCache;

    public RegexDetector(
            String kind,
            ClassifierField field,
            String severity,
            boolean wordBoundary,
            DeterministicNlPhraseCompiler compiler,
            ObjectMapper mapper) {
        this.kind = kind;
        this.defaultField = field;
        this.severity = severity;
        this.defaultWordBoundary = wordBoundary;
        this.compiler = compiler;
        this.mapper = mapper;
        this.compiledCache = new ConcurrentHashMap<>();
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
        if (shape == null) return List.of();
        List<String> phrases = shape.phrases();
        if (phrases == null || phrases.isEmpty()) return List.of();
        Boolean wb = shape.wordBoundary();
        boolean wordBoundary = wb == null ? defaultWordBoundary : wb;
        List<Pattern> overrides = new ArrayList<>(phrases.size());
        for (String phrase : phrases) {
            if (!phrase.isBlank()) overrides.add(compiled(phrase, wordBoundary));
        }
        return overrides;
    }

    private Pattern compiled(String phrase, boolean wordBoundary) {
        String key = wordBoundary + " " + phrase;
        Pattern cached = compiledCache.get(key);
        if (cached != null) return cached;
        if (compiledCache.size() >= MAX_CACHED_PATTERNS) compiledCache.clear();
        return compiledCache.computeIfAbsent(key, k -> compiler.compile(phrase, wordBoundary));
    }

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
        return mapper.valueToTree(Map.of(
                        "matched", span,
                        "pattern", pattern.pattern(),
                        "field", field.name().toLowerCase(Locale.ROOT)))
                .toString();
    }

    /**
     * {@code ignoreUnknown} is load-bearing: {@code config_json} is shared with other features and the platform mapper
     * fails on unknown properties, which once left the detector silently matching nothing.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ConfigShape(
            @Nullable List<String> phrases,
            @Nullable String field,

            @com.fasterxml.jackson.annotation.JsonProperty("word_boundary") @Nullable
            Boolean wordBoundary) {}
}
