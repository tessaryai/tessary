// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

/**
 * Builders for the shape ingest actually stores in {@code observation.input}/{@code output}: the
 * role-tagged {@code gen_ai} message envelope, not the clean text a signal detector ultimately
 * scores. Signal tests use these so they exercise the real production shape — the gap that let a
 * prompt-injection encoder score the envelope JSON structure itself and fire on benign traffic.
 *
 * <p>Both the {@code content} string form and the {@code parts[]} form are produced by real
 * instrumentation, so both are offered here.
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

    /** A message with a {@code parts[]} content array (the other real instrumentation shape). */
    public static String assistantParts(String text) {
        return "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":" + quote(text)
                + "}],\"finish_reason\":\"stop\"}]";
    }

    private static String envelope(String role, String text) {
        return "[{\"role\":\"" + role + "\",\"content\":" + quote(text) + "}]";
    }

    /** Minimal JSON string escaping for the fixture (quotes and backslashes). */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
