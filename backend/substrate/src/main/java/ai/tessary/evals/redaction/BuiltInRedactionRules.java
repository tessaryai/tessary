// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.redaction;

import java.util.List;

/**
 * The platform-seeded default redaction rules, reconciled onto every project by {@link
 * RedactionService#ensureSeeded} — on the write path as well as on a read of the rule set, so a project that
 * never opens the playground still gets them. A project may DISABLE a built-in rule (it is a default, not a
 * mandate) but not edit or delete it — the platform owns the pattern. Custom rules layer on top.
 *
 * <p>Patterns are intentionally conservative (precise over greedy) to minimize false positives on
 * non-PII text; the playground lets an operator tune coverage with custom rules.
 *
 * <p><b>Two classes of default, and the second is the one this product needs.</b> Rules 10–50 are the
 * regulated-PII set every telemetry vendor ships. Rules 60–110 are <em>credentials</em>, and they exist
 * because of what we ingest rather than because of a regulation: an agent's traces carry tool arguments,
 * tool results, HTTP headers and environment dumps, so the highest-severity thing likely to be sitting in a
 * span body is a live secret, not a phone number. Each is anchored on a vendor's own key prefix (or PEM
 * framing), which is what keeps them precise enough to run by default — a generic
 * "forty base64 characters" rule would redact half of every base64 payload we hold.
 *
 * <p>Adding a template here reaches existing projects: {@link RedactionService#ensureSeeded} inserts
 * built-ins missing <em>by name</em> rather than skipping a project that already has any rule at all.
 * Renaming one therefore re-seeds it as a second row, so names are as immutable as the patterns.
 */
public final class BuiltInRedactionRules {

    private BuiltInRedactionRules() {}

    /** A built-in rule template: stable name, regex, replacement, and application order. */
    public record Template(String name, String pattern, String replacement, int sortOrder) {}

    public static final List<Template> TEMPLATES = List.of(
            // The quantifiers are BOUNDED, and that is a performance fix, not pedantry. With
            // unbounded `+` this pattern is O(n^2) on text that contains no email: `[A-Za-z0-9.-]+`
            // can consume the whole remainder before failing to find `\.[A-Za-z]{2,}`, and find()
            // then retries from the next start position — an O(n) scan at each of n positions.
            // Measured on a non-matching body: 8 KB = 172 ms, 64 KB = 11.5 s, 128 KB = 46 s.
            // Bounding each part to its RFC 5321 limit (local-part 64, domain 255) makes the work
            // at each start position O(1), so the whole scan is O(n): the same 128 KB body drops
            // to 44 ms, ~1000x faster, with byte-identical matches.
            // This is what saturated production on 2026-07-31 — trace bodies are large by design
            // (see the never-truncate rule in AGENTS.md), so the quadratic term dominates.
            new Template(
                    "Email address",
                    "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}",
                    "[REDACTED_EMAIL]",
                    10),
            new Template("US Social Security number", "\\b\\d{3}-\\d{2}-\\d{4}\\b", "[REDACTED_SSN]", 20),
            // The lookarounds are load-bearing, and the bug they fix was found by running the rule set over
            // this repo's own trace samples: `\b(?:\d[ -]*?){13,16}\b` matched the SIXTEEN FRACTIONAL DIGITS
            // of `0.7799999999999999`, so a retrieval score in a stored trace became `0.[REDACTED_CARD]`.
            // A word boundary sits between `.` and `7`, which is why the old anchor did not stop it. Floats
            // like that are everywhere in agent telemetry — scores, probabilities, costs — and the damage is
            // silent, because redaction is a substitution and nothing downstream can tell it happened.
            // Refusing a leading or trailing digit-or-dot means the run must be the whole number.
            new Template("Credit card number", "(?<![\\d.])(?:\\d[ -]?){12,15}\\d(?![\\d.])", "[REDACTED_CARD]", 30),
            new Template(
                    "Phone number",
                    "\\b(?:\\+?\\d{1,3}[ .-]?)?(?:\\(\\d{3}\\)|\\d{3})[ .-]?\\d{3}[ .-]?\\d{4}\\b",
                    "[REDACTED_PHONE]",
                    40),
            new Template("IPv4 address", "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "[REDACTED_IP]", 50),

            // ---- credentials: prefix-anchored, so precision comes from the vendor's own format ----

            // Scheme AND token, replaced wholesale, because RedactionEngine substitutes replacements
            // LITERALLY (Matcher.quoteReplacement) — deliberately, so an operator-authored replacement can
            // never inject a backreference. There is therefore no way to keep the scheme and redact only
            // what follows it, and losing "Bearer" is the cheaper half of that trade.
            // The 20-character floor is what keeps this off English prose: "bearer of bad news" and
            // "Basic authentication" both fail it, while no real credential is that short.
            new Template(
                    "Authorization credential",
                    "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{20,4096}|\\bbasic\\s+[A-Za-z0-9+/=]{20,4096}",
                    "[REDACTED_CREDENTIAL]",
                    60),
            // Three base64url segments with the JOSE header's fixed opening — `eyJ` is `{"` encoded, so a
            // JWT is self-identifying and this cannot match arbitrary base64.
            new Template(
                    "JSON Web Token",
                    "\\beyJ[A-Za-z0-9_-]{4,4096}\\.[A-Za-z0-9_-]{4,8192}\\.[A-Za-z0-9_-]{4,4096}",
                    "[REDACTED_JWT]",
                    70),
            // One alternation rather than a rule per vendor: the list is long, it grows, and a project's
            // rule list is a thing a human reads. Ours (`tsy_`) is in it — an agent that talks to this
            // platform holds a write-scoped ingest token, and it would otherwise land in its own traces.
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
            // Keyed on the assignment rather than the value: `api_key = <anything>` is the shape a secret
            // takes when the vendor has no prefix to anchor on. The key name goes with the value for the
            // same literal-replacement reason as rule 60; the token still says what was removed.
            // The value class stops at whitespace, quote, comma, semicolon and brace, so this redacts one
            // field of a JSON body rather than the remainder of it. It also refuses a value that is
            // already a redaction token: this rule runs after the credential rules above, and without
            // the lookahead `api_key=AKIA...` came out as [REDACTED_SECRET], which the secret_leak
            // detector reads at its LOW band instead of the API-key token's HIGH (#1044).
            new Template(
                    "Secret assignment",
                    "(?i)\\b(?:api[_-]?key|secret|password|passwd|access[_-]?token|refresh[_-]?token"
                            + "|client[_-]?secret)\"?\\s{0,8}[:=]\\s{0,8}\"?(?!\\[REDACTED_)[^\\s\"',;{}]{6,4096}",
                    "[REDACTED_SECRET]",
                    90),
            // The framing line only. Redacting the whole PEM body would mean an unbounded `.` across
            // newlines on every ingested body; killing the header is enough to make the key unusable and
            // obvious, and it costs a fixed-width match.
            new Template(
                    "Private key block",
                    "-----BEGIN (?:[A-Z ]{0,32} )?PRIVATE KEY-----",
                    "[REDACTED_PRIVATE_KEY]",
                    100));
}
