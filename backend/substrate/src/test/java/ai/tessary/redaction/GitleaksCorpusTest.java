// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.redaction.GitleaksCorpus.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The vendored credential corpus: that every rule survives the move to Java, that it finds credentials the way
 * gitleaks does, and that the per-name fast path finds exactly what a scan of the whole text would.
 *
 * <p>The credentials below are FAKE: structurally valid shapes minted for this test, exempted from this
 * repo's own secret scan in {@code .gitleaks.toml} with that reason.
 */
class GitleaksCorpusTest {

    private static final GitleaksCorpus CORPUS = GitleaksCorpus.get();

    @Test
    void everyVendoredRuleCompilesInJava() {
        assertTrue(CORPUS.size() >= 220, "the pinned release plus the self-minted rules, not a stub: " + CORPUS.size());
        assertEquals(List.of(), CORPUS.uncompiled(), "a rule that does not compile would never run, silently");
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
    void overlappingRulesKeepTheMostSpecificName() {
        assertOnly("aws-access-token", true, "aws_api_key = \"AKIA" + "QYLPMN5HHHFPZAM2\"");
        assertOnly(
                "new-relic-browser-api-token",
                false,
                "NEW_RELIC_BROWSER_API_TOKEN = \"NRJS-3a7f9c2e1b8d4f6a0c5\"",
                "a vendor rule beats generic-api-key on the same characters");
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
    void theVendorNameFastPathFindsExactlyWhatAWholeTextScanFinds() {
        StringBuilder text = new StringBuilder();
        String[] lines = {
            "api_key = \"q7Zr2mK9xW4vN8pLr5Tq\"",
            "adobe_client_id = \"d8f2a6c4e8b0a2c4e6f8a0b2c4d6e8f0\"",
            "NEW_RELIC_BROWSER_API_TOKEN = \"NRJS-3a7f9c2e1b8d4f6a0c5\"",
            "the access token expires in an hour; ask for another",
            "config: {\"secret\": \"Zk8pQ2rT5wY8bE1hK4nR7uX0\", \"auth\": null}",
            "aws_api_key = \"AKIA" + "QYLPMN5HHHFPZAM2\" and a password: q7Zr2mK9xW4vN8pL",
            "a-very-long-identifier-name-that-runs-well-past-fifty-characters_api_key = \"Zk8pQ2rT5wY8bE1hK4nR\"",
            "keyboard layout: dvorak, token count: 12, secret santa: bob",
        };
        for (int i = 0; i < 40; i++) {
            for (String line : lines) text.append(line).append('\n');
        }
        String corpus = text.toString();
        List<Finding> fast = CORPUS.find(corpus);
        assertFalse(fast.isEmpty(), "the fixture carries credentials, or this proves nothing");
        assertEquals(CORPUS.findExhaustive(corpus), fast);
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
}
