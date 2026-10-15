// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Triage rules on whether the claim is true, never on whether the change is welcome. There is no {@code none()}: a run
 * that did not happen yields null from {@link #parse} and the job retries; {@code blocked} is the agent's word for
 * that. @param summary one sentence: the schema caps it because an uncapped field produced five paragraphs @param
 * citations so the ruling is checkable rather than trusted
 */
public record BehaviorTriageVerdict(String verdict, String summary, List<Citation> citations) {

    /** Persisted and rendered as a receipt a person reads, not a log. */
    private static final int STDOUT_MAX = 4_000;

    /**
     * One shape for every kind of citation. Unknown fields are ignored on read, because older blobs still carry the
     * retired {@code recomputed} key. @param stdout null on an evidence pointer
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Citation(
            String path, String reason, @Nullable String stdout) {}

    public String action() {
        return FindingRow.TriageAction.of(verdict);
    }

    /**
     * Assigned from a method, not a text block: javac would inline a compile-time constant into every class that reads
     * it.
     */
    public static final String JSON_SCHEMA = schema();

    private static String schema() {
        return """
            {
              "type": "object",
              "properties": {
                "verdict": {
                  "type": "string",
                  "enum": ["positive", "negative", "blocked"],
                  "description": "positive: the claim holds. negative: it does not. blocked: you could not reach the tools at all; not a ruling, it fails the run so it can be retried."
                },
                "summary": {
                  "type": "string",
                  "maxLength": 320,
                  "description": "One sentence, under 320 characters, plain words, no em dashes: what happened, then why the claim holds or does not. On blocked, the call that failed."
                },
                "citations": {
                  "type": "array",
                  "description": "Everything the ruling rests on: the ids you opened, the get_finding fields you relied on, and every check script you ran.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "path": {"type": "string", "description": "An id you opened (trace:<id>, span:<trace_id>/<span_id>), a get_finding field (toolError.curRate, metric.ratio), or a script you ran (checks/by_model.py)."},
                      "reason": {"type": "string", "description": "What this established. If you read less than every row, which rows you read."},
                      "stdout": {"type": "string", "description": "For a script only: exactly what running it printed. Omit for an id or a field."}
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

    /** Null is not a verdict: the caller throws {@code TRIAGE_RUN_INCOMPLETE} and the job retries. */
    public static @Nullable BehaviorTriageVerdict parse(ObjectMapper mapper, String text) {
        JsonNode node = ruling(mapper, text);
        if (node == null) return null;
        String verdict = node.path("verdict").asText("");
        if (!FindingRow.TriageVerdict.POSITIVE.equals(verdict) && !FindingRow.TriageVerdict.NEGATIVE.equals(verdict)) {
            return null;
        }
        List<Citation> citations = new ArrayList<>();
        for (JsonNode c : node.path("citations")) {
            String path = c.path("path").asText("");
            if (path.isBlank()) continue;
            citations.add(new Citation(path, c.path("reason").asText(""), stdout(c.path("stdout"))));
        }
        // A ruling with nothing behind it is the fabrication this exists to avoid.
        if (citations.isEmpty()) return null;
        String summary = node.path("summary").asText("");
        return new BehaviorTriageVerdict(verdict, summary, List.copyOf(citations));
    }

    /** Read before {@link #parse}, which declines a {@code blocked} answer just like gibberish. */
    public static @Nullable String blockedReason(ObjectMapper mapper, String text) {
        JsonNode node = ruling(mapper, text);
        if (node == null || !BLOCKED.equals(node.path("verdict").asText(""))) return null;
        String reason = node.path("summary").asText("");
        return reason.isBlank() ? "the agent reported a missing prerequisite and did not name it" : reason;
    }

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
     * Nothing may ever write this to {@code finding.triage_verdict}, so it lives with the wire format, not the verdict
     * vocabulary.
     */
    static final String BLOCKED = "blocked";

    private static @Nullable String stdout(JsonNode node) {
        if (!node.isTextual()) return null;
        String out = node.asText();
        return out.isEmpty() ? null : out.length() <= STDOUT_MAX ? out : out.substring(0, STDOUT_MAX) + "\n…truncated";
    }

    private static @Nullable JsonNode tryReadTree(ObjectMapper mapper, @Nullable String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractObject(String text) {
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        return (open < 0 || close <= open) ? "" : text.substring(open, close + 1);
    }
}
