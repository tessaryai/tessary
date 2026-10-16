// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

/**
 * Builders for the shape ingest actually stores in {@code observation.input}/{@code output}: the
 * role-tagged {@code gen_ai} message envelope, not the clean text a signal detector ultimately
 * scores. Signal tests use these so they exercise the real production shape — the gap that let a
 * prompt-injection encoder score the envelope JSON structure itself and fire on benign traffic.
 *
 * <p>Real instrumentation also produces a {@code parts[]} content array; no test here needs that
 * form, so only the {@code content} string form is offered.
 */
public final class ClassifierObservations {

    private ClassifierObservations() {}

    /** A user turn as a gen_ai input envelope: {@code [{"role":"user","content":"…"}]}. */
    public static String userInput(String text) {
        return envelope("user", text);
    }

    /** An assistant turn as a gen_ai output envelope: {@code [{"role":"assistant","content":"…"}]}. */
    public static String assistantOutput(String text) {
        return envelope("assistant", text);
    }

    private static String envelope(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"content\":" + quote(text) + "}]";
    }

    /** Minimal JSON string escaping for the fixture (quotes and backslashes). */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
