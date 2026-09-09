// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structured result a triage run must emit (enforced via a JSON schema). The agent audits ONE
 * finding's CLAIM — is it true, was it sampled over enough of the population, does the evidence
 * carry it — and rules {@code positive}, {@code negative} or {@code unclear}.
 *
 * <p><b>It does not judge the change.</b> The old vocabulary ({@code expected} / {@code deviation})
 * asked whether the behaviour was legitimate, which is a question about the product's intent that an
 * agent with no repository cannot answer and an agent with one answers by guessing. A cost or duration
 * <em>drop</em> now passes this gate exactly like a rise: "improvement" is a judgement, and triage rules
 * on facts.
 *
 * <p><b>There is no {@code none()}.</b> A run that did not happen — no sandbox, an unparseable answer,
 * a launcher 401 — produces no verdict at all: {@link #parse} returns null, the engine throws, and the
 * job retries into the dead letter like every other job kind. That is what makes "unsure means close"
 * safe: every {@code unclear} on a finding was written by a run that actually read the evidence, so
 * closing on it costs a finding whose cause will re-open it if it is real.
 *
 * <p><b>{@code blocked} is how the agent joins that set.</b> The three verdicts are conclusions about
 * the evidence, and an agent that cannot reach the evidence has none to conclude from. Before this
 * existed the only way it could report that was to rule {@code unclear}, which is a finding-closing
 * judgement — and one such run closed a finding, and folded 7,191 calls into a detector's reference,
 * on the strength of a read surface it never reached. So the schema gives the failure its own word:
 * {@link #blockedReason} recognises it, {@link #parse} refuses to build a verdict from it, and the
 * engine turns it into the same retryable failure as a launcher that never answered.
 *
 * @param verdict {@link FindingRow.TriageVerdict}
 * @param summary one sentence a human reading the case can act on. The schema caps it and the prompt
 *     asks for it, because neither alone held: with only a bare {@code "type": "string"} the agent wrote
 *     a five-paragraph analysis, every surface that renders a finding printed the lot, and the one fact
 *     a reader needed sat in the middle of it.
 * @param citations what the ruling rests on — evidence pointers, the trace and span ids the agent
 *     fetched, and the check scripts it wrote and ran — so the ruling is checkable rather than trusted
 */
public record BehaviorTriageVerdict(String verdict, String summary, List<Citation> citations) {

    /**
     * How much of a check script's output is kept. The stdout is persisted on the finding and rendered
     * beside the ruling, so it is a receipt a person reads, not a log: a script that printed a megabyte
     * has already failed rule 5's "state the sample taken".
     */
    private static final int STDOUT_MAX = 4_000;

    /**
     * One piece of what the ruling rests on.
     *
     * <p>ONE shape whatever the citation is. {@code path} is a dotted pointer into the dossier
     * ({@code window.n_cur}), an id the agent fetched over MCP ({@code trace:0f3e…}), or the script it
     * wrote under {@code checks/}. Separate columns would have bought a type distinction and cost every
     * reader a branch.
     *
     * @param stdout what the script printed, for a citation that IS a script — the receipt that makes a
     *     computed claim re-checkable by the person reading the case. Null on an evidence pointer.
     * @param recomputed detector numbers this check re-derived from the substrate. The platform compares
     *     each against the finding's own payload and ABORTS the run on a disagreement rather than
     *     recording the ruling: one of the two is wrong, we cannot tell which from here, and a ruling
     *     built on a silently wrong script is the worst thing this lane can produce.
     */
    public record Citation(
            String path, String reason, @Nullable String stdout, List<Recomputed> recomputed) {

        /** Absent is empty, including when Jackson reads back a blob written before this field existed. */
        public Citation {
            recomputed = recomputed == null ? List.of() : List.copyOf(recomputed);
        }

        /** An evidence pointer or an id — a citation with nothing computed behind it. */
        public static Citation of(String path, String reason) {
            return new Citation(path, reason, null, List.of());
        }
    }

    /**
     * One number a check script re-derived, named by where it sits in the detector's own payload.
     *
     * @param pointer a dotted path into {@code dossier/state.json} ({@code window.n_cur},
     *     {@code quantiles.p95[1]}) — the platform resolves it against the finding's payload
     * @param value what the script computed for it
     */
    public record Recomputed(String pointer, double value) {}

    /** The action this verdict fixes. One mapping, defined once, in {@link FindingRow.TriageAction}. */
    public String action() {
        return FindingRow.TriageAction.of(verdict);
    }

    /**
     * The schema the CLI is constrained to (passed via {@code --json-schema}).
     *
     * <p>Assigned from a method rather than a text block directly, and that is load-bearing rather
     * than style: javac inlines a compile-time constant into <em>every</em> class file that reads it,
     * so a 2 kB schema would ship a full copy inside the engine and its test. {@link
     * ai.tessary.observer.AgentVerdict#JSON_SCHEMA} escapes the same trap by going through
     * {@code String.format}; this one has nothing to interpolate, so it says so instead.
     */
    public static final String JSON_SCHEMA = schema();

    private static String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "verdict": {
                  "type": "string",
                  "enum": ["positive", "negative", "unclear", "blocked"],
                  "description": "positive: the claim holds. negative: it does not. unclear: you read the evidence and it does not settle the question. blocked: a prerequisite was missing and you could not read the evidence at all — this is not a ruling, it fails the run so it can be retried later."
                },
                "summary": {
                  "type": "string",
                  "maxLength": 320,
                  "description": "ONE sentence a human can act on: what the claim is and why it holds or does not. On `blocked`, name the missing prerequisite instead. Not a report — the evidence is already on the screen beside this."
                },
                "citations": {
                  "type": "array",
                  "description": "Everything the ruling rests on: the evidence you opened, the ids you fetched, and EVERY check script you wrote.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "path": {"type": "string", "description": "A dotted pointer into dossier/state.json (window.n_cur), an id you fetched (trace:<id>, span:<trace_id>/<span_id>), or the script you wrote (checks/rate.py)."},
                      "reason": {"type": "string", "description": "What this established, and for a sample, what you took."},
                      "stdout": {"type": "string", "description": "For a check script only: exactly what running it printed. Omit for an evidence pointer."},
                      "recomputed": {
                        "type": "array",
                        "description": "For a check script only: the detector's own numbers this script re-derived. A value that disagrees with the payload ABORTS the run — do not report one you did not compute.",
                        "items": {
                          "type": "object",
                          "properties": {
                            "pointer": {"type": "string", "description": "Where the number sits in dossier/state.json, dotted, copied from the path it has in that file rather than from this example: window.n_cur, rate.cur, quantiles.p95[1]."},
                            "value": {"type": "number", "description": "What your script computed for it."}
                          },
                          "required": ["pointer", "value"],
                          "additionalProperties": false
                        }
                      }
                    },
                    "required": ["path", "reason"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["verdict", "summary", "citations"],
              "additionalProperties": false
            }
            """;
    }

    /**
     * Parse the agent's output, tolerant of (a) raw schema JSON and (b) a {@code --output-format json}
     * envelope whose {@code structured_output} or {@code result} holds it.
     *
     * <p>Returns null when there is no ruling in the text. Null is NOT {@code unclear}: an unparseable
     * answer means the run failed to produce one, and recording {@code unclear} for it would close a
     * finding on the strength of a broken run.
     */
    public static @Nullable BehaviorTriageVerdict parse(ObjectMapper mapper, String text) {
        JsonNode node = ruling(mapper, text);
        if (node == null) return null;
        try {
            String verdict = node.path("verdict").asText("");
            if (!FindingRow.TriageVerdict.POSITIVE.equals(verdict)
                    && !FindingRow.TriageVerdict.NEGATIVE.equals(verdict)
                    && !FindingRow.TriageVerdict.UNCLEAR.equals(verdict)) {
                return null;
            }
            List<Citation> citations = new ArrayList<>();
            for (JsonNode c : node.path("citations")) {
                String path = c.path("path").asText("");
                if (path.isBlank()) continue;
                citations.add(new Citation(
                        path, c.path("reason").asText(""), stdout(c.path("stdout")), recomputed(c.path("recomputed"))));
            }
            String summary = node.path("summary").asText("");
            // A ruling with nothing behind it is exactly the fabrication this analysis exists to avoid,
            // so it is downgraded rather than trusted. Downgraded to `unclear`, which now CLOSES the
            // finding — an uncited claim is not evidence a case should be opened on, and recurrence is
            // what brings a real one back.
            if (citations.isEmpty() && !FindingRow.TriageVerdict.UNCLEAR.equals(verdict)) {
                return new BehaviorTriageVerdict(FindingRow.TriageVerdict.UNCLEAR, summary, List.of());
            }
            return new BehaviorTriageVerdict(verdict, summary, List.copyOf(citations));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The agent's own report that it could not run, and why — null when the answer is anything else,
     * including unparseable.
     *
     * <p>Read BEFORE {@link #parse}, because the two disagree about this text on purpose: {@code parse}
     * declines a {@code blocked} answer the same way it declines gibberish, and only this can tell the
     * caller that the agent said something specific and actionable.
     */
    public static @Nullable String blockedReason(ObjectMapper mapper, String text) {
        JsonNode node = ruling(mapper, text);
        if (node == null || !BLOCKED.equals(node.path("verdict").asText(""))) return null;
        String reason = node.path("summary").asText("");
        return reason.isBlank() ? "the agent reported a missing prerequisite and did not name it" : reason;
    }

    /**
     * The answer the agent emitted, whichever envelope the CLI wrapped it in — raw schema JSON, an
     * {@code --output-format json} envelope with {@code structured_output}, or one with {@code result}
     * holding the JSON as text. Null when there is no object carrying a {@code verdict} anywhere in it.
     */
    private static @Nullable JsonNode ruling(ObjectMapper mapper, @Nullable String text) {
        if (text == null || text.isBlank()) return null;
        JsonNode node = tryReadTree(mapper, text);
        if (node == null) node = tryReadTree(mapper, extractObject(text));
        if (node == null || !node.isObject()) return null;

        if (node.path("structured_output").isObject()) {
            node = node.get("structured_output");
        } else if (!node.has("verdict") && node.has("result")) {
            JsonNode result = node.get("result");
            JsonNode unwrapped = result.isTextual() ? tryReadTree(mapper, extractObject(result.asText())) : result;
            if (unwrapped != null && unwrapped.isObject()) node = unwrapped;
        }
        return node.isObject() && node.has("verdict") ? node : null;
    }

    /**
     * Not a {@link FindingRow.TriageVerdict}, and deliberately not stored beside them: nothing may ever
     * write this to {@code finding.triage_verdict}, so it lives here, where the wire format is defined,
     * rather than in the vocabulary of things a finding can be ruled.
     */
    static final String BLOCKED = "blocked";

    private static @Nullable String stdout(JsonNode node) {
        if (!node.isTextual()) return null;
        String out = node.asText();
        return out.isEmpty() ? null : out.length() <= STDOUT_MAX ? out : out.substring(0, STDOUT_MAX) + "\n…truncated";
    }

    /**
     * The re-derived numbers, skipping anything that is not a pointer paired with a number — a
     * malformed entry cannot contradict the payload, and dropping it silently is right here: the
     * contradiction check is a safety net over the agent's own arithmetic, not a schema validator.
     */
    private static List<Recomputed> recomputed(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Recomputed> out = new ArrayList<>();
        for (JsonNode r : node) {
            String pointer = r.path("pointer").asText("");
            JsonNode value = r.path("value");
            if (!pointer.isBlank() && value.isNumber()) out.add(new Recomputed(pointer, value.asDouble()));
        }
        return List.copyOf(out);
    }

    private static @Nullable JsonNode tryReadTree(ObjectMapper mapper, @Nullable String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** The substring from the first '{' to the last '}', or "" if none — strips surrounding prose. */
    private static String extractObject(String text) {
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        return (open < 0 || close <= open) ? "" : text.substring(open, close + 1);
    }
}
