// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import java.util.List;

/**
 * A project may disable a built-in rule but not edit or delete it. Credentials rules exist because agent traces carry
 * tool args, headers and env dumps; each is anchored on a vendor prefix to stay precise enough to run by default.
 * {@link RedactionService#ensureSeeded} inserts missing built-ins by name, so names are as immutable as patterns.
 */
public final class BuiltInRedactionRules {

    private BuiltInRedactionRules() {}

    public record Template(String name, String pattern, String replacement, int sortOrder) {}

    /**
     * First on purpose: a PII rule running earlier could rewrite part of a credential (the card rule matches digits
     * inside a token). The prefix rules below stay for what the corpus does not name.
     */
    public static final Template CREDENTIALS =
            new Template("Credentials", GitleaksCorpus.get().patternValue(), "[REDACTED_SECRET]", 5);

    public static final List<Template> TEMPLATES = List.of(
            CREDENTIALS,
            // Bounded quantifiers are a performance fix: unbounded `+` made this O(n^2) on email-free text (128 KB took
            // 46 s) and saturated production; RFC 5321 bounds make it O(n) with identical matches.
            new Template(
                    "Email address",
                    "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}",
                    "[REDACTED_EMAIL]",
                    10),
            new Template("US Social Security number", "\\b\\d{3}-\\d{2}-\\d{4}\\b", "[REDACTED_SSN]", 20),
            // The lookarounds stop the rule matching the fractional digits of a float such as `0.7799999999999999`,
            // which silently corrupted scores in stored traces.
            new Template("Credit card number", "(?<![\\d.])(?:\\d[ -]?){12,15}\\d(?![\\d.])", "[REDACTED_CARD]", 30),
            new Template(
                    "Phone number",
                    "\\b(?:\\+?\\d{1,3}[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}\\b",
                    "[REDACTED_PHONE]",
                    40),
            new Template("IPv4 address", "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "[REDACTED_IP]", 50),

            // Replacements are literal (Matcher.quoteReplacement) so an operator cannot inject a backreference, so the
            // scheme goes with the token. The 20-character floor keeps this off prose like "bearer of bad news".
            new Template(
                    "Authorization credential",
                    "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,4096}|\\bbasic\\s+[A-Za-z0-9+/=]{20,4096}",
                    "[REDACTED_CREDENTIAL]",
                    60),
            // `eyJ` is `{"` encoded, so a JWT is self-identifying and this cannot match arbitrary base64.
            new Template(
                    "JSON Web Token",
                    "\\beyJ[A-Za-z0-9_-]{4,4096}\\.[A-Za-z0-9_-]{4,8192}\\.[A-Za-z0-9_-]{4,4096}",
                    "[REDACTED_JWT]",
                    70),
            // Includes our own `tsy_`: an agent talking to this platform holds an ingest token that would otherwise
            // land in its own traces.
            new Template(
                    "Provider API key",
                    "\\b(?:sk-ant-[A-Za-z0-9_-]{16,256}"
                            + "|sk-[A-Za-z0-9_-]{16,256}"
                            + "|AKIA[0-9A-Z]{16}"
                            + "|ASIA[0-9A-Z]{16}"
                            + "|gh[pousr]_[A-Za-z0-9]{16,255}"
                            + "|github_pat_[A-Za-z0-9_]{16,255}"
                            + "|xox[baprs]-[A-Za-z0-9-]{10,255}"
                            + "|tsy_[A-Za-z0-9_-]{16,255}"
                            + "|AIza[A-Za-z0-9_-]{20,64})",
                    "[REDACTED_API_KEY]",
                    80),
            // The key name goes with the value (literal replacement). The value stops at whitespace, quotes, commas,
            // semicolons and braces, so one JSON field is redacted. The lookahead refuses an existing redaction token,
            // which secret_leak would otherwise read at its LOW band.
            new Template(
                    "Secret assignment",
                    "(?i)\\b(?:api[_-]?key|secret|password|passwd|access[_-]?token|refresh[_-]?token"
                            + "|client[_-]?secret)\"?\\s{0,8}[:=]\\s{0,8}\"?(?!\\[REDACTED_)[^\\s\"',;{}]{6,4096}",
                    "[REDACTED_SECRET]",
                    90),
            // The framing line only: the whole body would need an unbounded multiline match, and killing the header
            // already makes the key unusable.
            new Template(
                    "Private key block",
                    "-----BEGIN (?:[A-Z ]{0,32} )?PRIVATE KEY-----",
                    "[REDACTED_PRIVATE_KEY]",
                    100));
}
