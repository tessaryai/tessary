// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.redaction.GitleaksCorpus.Finding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The vendored credential corpus: that every rule survives the move to Java, that it finds credentials the way
 * gitleaks does, and that the per-name fast path finds what a scan of the whole text would.
 *
 * <p>The credentials below are FAKE: structurally valid shapes minted for this test, exempted from this
 * repo's own secret scan in {@code .gitleaks.toml} with that reason.
 */
class GitleaksCorpusTest {

    private static final GitleaksCorpus CORPUS = GitleaksCorpus.get();

    @Test
    void everyVendoredRuleCompilesInJava() throws Exception {
        JsonNode rules;
        try (InputStream in = GitleaksCorpusTest.class.getClassLoader().getResourceAsStream(GitleaksCorpus.RESOURCE)) {
            rules = new ObjectMapper().readTree(in).path("rules");
        }
        assertTrue(rules.size() >= 220, "the pinned release plus the self-minted rules, not a stub: " + rules.size());
        for (JsonNode rule : rules) {
            String id = rule.path("id").asText();
            assertDoesNotThrow(
                    () -> Pattern.compile(
                            GitleaksCorpus.toJava(rule.path("regex").asText())),
                    "a rule that does not compile would never run, silently: " + id);
        }
    }

    @Test
    void theVendoredSelfMintedRulesAreTheOnesTheRepoScanUses() throws Exception {
        Path source = Path.of("../../scripts/lib/gitleaks-self-minted.toml");
        String vendored;
        try (InputStream in = GitleaksCorpusTest.class.getClassLoader().getResourceAsStream(GitleaksCorpus.RESOURCE)) {
            vendored =
                    new ObjectMapper().readTree(in).path("self_minted_sha256").asText();
        }
        String current =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        assertEquals(
                current,
                vendored,
                "scripts/lib/gitleaks-self-minted.toml changed without re-running scripts/sync-gitleaks.sh, so"
                        + " redaction and the repo's secret scan now disagree about what a credential is");
    }

    @Test
    void re2ConstructsJavaSpellsDifferentlyAreTranslated() {
        assertEquals("(abc)", GitleaksCorpus.toJava("(?P<name>abc)"), "a named group keeps its number");
        assertEquals("(abc)", GitleaksCorpus.toJava("(?<key_ops>abc)"), "Java rejects underscores in group names");
        assertEquals("[\\p{Alnum}]{4}", GitleaksCorpus.toJava("[[:alnum:]]{4}"));
        assertEquals(
                "^\\$(?:\\d+|\\{\\d+})$",
                GitleaksCorpus.toJava("^\\$(?:\\d+|{\\d+})$"),
                "RE2 reads a lone { as literal");
        assertEquals("[\\w.-]{0,50}?", GitleaksCorpus.toJava("[\\w.-]{0,50}?"), "a real repetition is left alone");
        assertEquals("(?<=x)", GitleaksCorpus.toJava("(?<=x)"), "a lookbehind is not a named group");
        Pattern.compile(GitleaksCorpus.toJava("^\\$(?:\\d+|{\\d+})$"));
    }

    @Test
    void providerFormatsAreFoundAnchoredAndByTheirOwnName() {
        assertEquals(
                List.of(new Finding("aws-access-token", true, 8, 28)),
                CORPUS.find("aws key AKIA" + "QYLPMN5HHHFPZAM2 here"));
        assertOnly("github-pat", true, "token: ghp_aB3dE5gH7jK9mN1pQ3sT5vX7zA9cE1gI3kM5");
        assertOnly("stripe-access-token", true, "sk_live_" + "4eC39HqLyjWDarjtT1zdp7dc");
        assertOnly("slack-bot-token", true, "xoxb-" + "123456789012-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx");
        assertOnly("tessary-api-key", true, "use tsy_w_Q7zR2mK9xW4vN8pLr5TqAB here");
    }

    @Test
    void aVendorNameBesideARandomStringIsFoundUnanchored() {
        assertOnly("generic-api-key", false, "api_key = \"q7Zr2mK9xW4vN8pLr5Tq\"");
        assertOnly("adobe-client-id", false, "adobe_client_id = \"d8f2a6c4e8b0a2c4e6f8a0b2c4d6e8f0\"");
    }

    @Test
    void placeholdersLowEntropyValuesAndProseAreNotCredentials() {
        assertEquals(List.of(), CORPUS.find("api_key = \"${API_KEY}\""), "a template reference is allowlisted");
        assertEquals(List.of(), CORPUS.find("api_key = $API_KEY"), "an environment reference is allowlisted");
        assertEquals(List.of(), CORPUS.find("password = \"aaaaaaaaaaaaaaaaaaaa\""), "below the entropy floor");
        assertEquals(List.of(), CORPUS.find("A password is required to continue."), "prose");
        assertEquals(List.of(), CORPUS.find("tsy_w_xxxxxxxxxxxxxxxxxxxxxx"), "a documented placeholder token");
    }

    @Test
    void theReplacedSpanIsTheSecretForOneGroupAndTheWholeMatchForMany() {
        String assigned = "TESSARY_DB_PASSWORD=hunter2hunter2";
        Finding db = CORPUS.find(assigned).get(0);
        assertEquals("hunter2hunter2", assigned.substring(db.start(), db.end()), "secret_group names the password");

        // jwt-base64 captures thirty-odd header fragments, and gitleaks' "secret" is the first of them. Replacing
        // that would leave the rest of the token readable, so the whole match goes.
        String token = "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnpkV0lpT2lJeE1qTTBOVFkzT0Rrd0lpd2libUZ0"
                + "WlNJNklrcHZhRzRnUkc5bElpd2lhV0YwSWpveE5URTJNak01TURJeWZRLlNmbEt4d1JKU01lS0tGMlFUNGZ3cE1lSmYzNlBPaz"
                + "Z5SlZfYWRRc3N3NWM=";
        String text = "encoded " + token + " end";
        Finding jwt = CORPUS.find(text).get(0);
        assertEquals("jwt-base64", jwt.ruleId());
        assertEquals(token, text.substring(jwt.start(), jwt.end()));
    }

    @Test
    void theVendorNameFastPathFindsWhatAWholeTextScanFinds() {
        // The expected list is what a scan of every rule over the whole text found here, recorded when that
        // reference scan still existed beside the fast path.
        String text = String.join(
                "\n",
                "api_key = \"q7Zr2mK9xW4vN8pLr5Tq\"",
                "adobe_client_id = \"d8f2a6c4e8b0a2c4e6f8a0b2c4d6e8f0\"",
                "NEW_RELIC_BROWSER_API_TOKEN = \"NRJS-3a7f9c2e1b8d4f6a0c5\"",
                "the access token expires in an hour; ask for another",
                "config: {\"secret\": \"Zk8pQ2rT5wY8bE1hK4nR7uX0\", \"auth\": null}",
                "aws_api_key = \"AKIA" + "QYLPMN5HHHFPZAM2\" and a password: q7Zr2mK9xW4vN8pL",
                "a-very-long-identifier-name-that-runs-well-past-fifty-characters_api_key = \"Zk8pQ2rT5wY8bE1hK4nR\"",
                "keyboard layout: dvorak, token count: 12, secret santa: bob");
        List<String> found = CORPUS.find(text).stream()
                .map(f -> f.ruleId() + " " + text.substring(f.start(), f.end()))
                .toList();
        assertEquals(
                List.of(
                        "generic-api-key q7Zr2mK9xW4vN8pLr5Tq",
                        "adobe-client-id d8f2a6c4e8b0a2c4e6f8a0b2c4d6e8f0",
                        "new-relic-browser-api-token NRJS-3a7f9c2e1b8d4f6a0c5",
                        "generic-api-key Zk8pQ2rT5wY8bE1hK4nR7uX0",
                        "aws-access-token AKIA" + "QYLPMN5HHHFPZAM2",
                        "generic-api-key q7Zr2mK9xW4vN8pL",
                        "generic-api-key Zk8pQ2rT5wY8bE1hK4nR"),
                found);
    }

    @Test
    void aKeywordDenseMegabyteScansInBoundedTime() {
        StringBuilder text = new StringBuilder();
        String line = "Set the api_key in your config and pass the token to the client; the secret rotates. "
                + "curl -H \"Authorization: Bearer $TOKEN\" https://api.example.com password=None key=None\n";
        while (text.length() < 1_000_000) text.append(line);
        String megabyte = text.toString();
        CORPUS.find(megabyte); // warm
        // Generous on purpose: this guards against the per-position regex scan coming back, which cost several
        // times this, not against a slow CI runner.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CORPUS.find(megabyte));
    }

    private static void assertOnly(String rule, boolean anchored, String text) {
        assertOnly(rule, anchored, text, text);
    }

    private static void assertOnly(String rule, boolean anchored, String text, String message) {
        List<Finding> found = CORPUS.find(text);
        assertEquals(1, found.size(), message + " -> " + found);
        assertEquals(rule, found.get(0).ruleId(), message);
        assertEquals(anchored, found.get(0).anchored(), message);
    }

    /**
     * Every POSIX bracket class keeps its POSIX meaning in Java: one character the class must admit and one
     * it must not, as code points. A class translated to its neighbour ({@code print} to {@code graph} drops
     * the space, {@code word} to {@code alnum} drops the underscore) would silently change what a rule finds.
     */
    @ParameterizedTest
    @CsvSource({
        "alnum, 55, 95",
        "alpha, 113, 49",
        "digit, 55, 97",
        "lower, 97, 65",
        "upper, 65, 97",
        "space, 9, 120",
        "punct, 33, 97",
        "xdigit, 102, 103",
        "word, 95, 45",
        "blank, 9, 10",
        "cntrl, 7, 97",
        "graph, 126, 32",
        "print, 32, 7"
    })
    void posixClassesKeepTheirMeaning(String posix, int inside, int outside) {
        Pattern p = Pattern.compile(GitleaksCorpus.toJava("[[:" + posix + ":]]"));
        assertTrue(p.matcher(Character.toString(inside)).matches(), posix + " admits " + inside);
        assertFalse(p.matcher(Character.toString(outside)).matches(), posix + " refuses " + outside);
    }

    /**
     * Loading a corpus drops what Java cannot compile without dropping the rule around it: a rule whose regex
     * does not compile is left out, and an allowlist regex that does not compile allows nothing, so the rule
     * it guards still runs. The keyword-context rule's vendor name here carries an escaped paren, which the
     * name scan has to step over rather than count as a group opening.
     */
    @Test
    void loadSkipsWhatWillNotCompileButKeepsTheRuleAnUnusableAllowlistGuards() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode rule = json.createObjectNode()
                .put("id", "acme-token")
                .put(
                        "regex",
                        "(?i)[\\w.-]{0,50}?(?:ac\\(me)(?:[ \\t\\w.-]{0,20})[\\s'\"]{0,3}(?:=|:)[\\s'\"]{0,5}([a-z0-9]{16})\\b");
        rule.putArray("keywords").add("ac(me");
        rule.putArray("allowlists").addObject().putArray("regexes").add("(bad");
        ObjectNode doc = json.createObjectNode().put("version", "test");
        doc.putArray("rules")
                .add(json.createObjectNode().put("id", "broken").put("regex", "(unclosed"))
                .add(rule);

        GitleaksCorpus corpus = GitleaksCorpus.load(doc);

        assertEquals(List.of(new Finding("acme-token", false, 12, 28)), corpus.find("ac(me_key = 0123456789abcdef"));
    }
}
