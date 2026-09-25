// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.ClassifierField;
import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.substrate.SubstrateObservation;
import ai.tessary.testsupport.ClassifierObservations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit acceptance for {@link RegexDetector}: an NL phrase from {@code config_json}, compiled once to a
 * regex, fires over the selected observation field with no model call; {@code config_json} also
 * overrides the field and word-boundary. Evidence is bounded JSON. Literal keyword matching stays per-observation and
 * field-restricted.
 */
class RegexDetectorTest {

    private static final String SECRET_KEY = "{\"phrases\":[\"secret key\"]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final NlPhraseCompiler compiler = new DeterministicNlPhraseCompiler();

    private RegexDetector detector(ClassifierField field, boolean wordBoundary) {
        return new RegexDetector(
                BuiltInDetector.Kind.REGEX, field, Detection.Severity.CRITICAL, wordBoundary, compiler, mapper);
    }

    private SubstrateObservation obs(String input, String output) {
        // Stored as ingest writes it — the gen_ai role envelope — so the detector scores the
        // flattened text the ClassifierField view yields, not clean strings production never emits.
        return new SubstrateObservation(
                "obs1",
                "proj1",
                "trace1",
                "sess1",
                null,
                null,
                "llm",
                "name",
                input == null ? null : ClassifierObservations.userInput(input),
                output == null ? null : ClassifierObservations.assistantOutput(output),
                null,
                "2026-06-11",
                null);
    }

    @Test
    void detect_firesOnConfiguredPhraseOverConfiguredField() {
        RegexDetector d = detector(ClassifierField.OUTPUT, true);
        Detection fired = d.detect(obs("benign prompt", "your secret key is sk-123"), SECRET_KEY);
        assertTrue(fired.fired(), "the compiled regex matches the literal phrase in the output");
        assertEquals(Detection.Severity.CRITICAL, fired.severity(), "severity is the detector's baked severity");
    }

    @Test
    void detect_doesNotFireWhenPhraseAbsent() {
        RegexDetector d = detector(ClassifierField.OUTPUT, true);
        assertFalse(d.detect(obs("benign", "all clear"), SECRET_KEY).fired(), "no match → no fire");
    }

    @Test
    void detect_respectsFieldSelection() {
        RegexDetector outputOnly = detector(ClassifierField.OUTPUT, false);
        String config = "{\"phrases\":[\"leak\"]}";
        assertFalse(
                outputOnly
                        .detect(obs("leak in the input", "clean output"), config)
                        .fired(),
                "OUTPUT field ignores a match that is only in the input");
        assertTrue(
                outputOnly.detect(obs("clean input", "a leak here"), config).fired(),
                "OUTPUT field fires on a match in the output");
    }

    @Test
    void detect_configJsonOverridesPhrasesFieldAndBoundary() {
        RegexDetector d = detector(ClassifierField.OUTPUT, true);
        String config = "{\"phrases\":[\"cat\"],\"field\":\"INPUT\",\"word_boundary\":false}";
        assertTrue(
                d.detect(obs("the category list", "nothing"), config).fired(),
                "config override switches phrase to 'cat', field to INPUT, and drops the word boundary");
    }

    @Test
    void detect_invalidConfigMatchesNothing() {
        RegexDetector d = detector(ClassifierField.OUTPUT, true);
        Detection fired = d.detect(obs("x", "your secret key here"), "{not valid json");
        assertFalse(fired.fired(), "malformed config_json carries no phrases, so nothing fires");
    }

    @Test
    void detect_evidenceIsBoundedJsonWithMatchAndPattern() throws Exception {
        RegexDetector d = detector(ClassifierField.OUTPUT, false);
        Detection fired = d.detect(obs("x", "here is the API KEY value"), "{\"phrases\":[\"api key\"]}");
        assertTrue(fired.fired(), "fires");
        var evidence = mapper.readTree(fired.evidenceJson());
        assertEquals("API KEY", evidence.get("matched").asText(), "evidence carries the matched span verbatim");
        assertTrue(evidence.has("pattern"), "evidence carries the compiled pattern");
        assertEquals("output", evidence.get("field").asText(), "evidence names the field matched");
    }

    @Test
    void configKeysOwnedByOtherFeaturesDoNotDisableTheDetector() {
        // `config_json` is one blob shared across features — the pre-deploy loop keys off `surfaces`
        // in the same object. The platform mapper is a bare `new ObjectMapper()`, so
        // FAIL_ON_UNKNOWN_PROPERTIES is ON; before ConfigShape ignored unknowns, one foreign key made
        // the parse throw, the catch returned null, and the detector had no phrases. The signal then
        // matched nothing at all, silently and with nothing logged.
        RegexDetector detector = detector(ClassifierField.BOTH, false);
        String config = "{\"surfaces\":[\"tool_definition\"],\"phrases\":[\"upstream exploded\"]}";

        assertTrue(
                detector.detect(obs("q", "upstream exploded"), config).fired(),
                "a foreign config key must not silently disable the phrases");
        assertFalse(detector.detect(obs("q", "all fine"), config).fired());
    }
}
