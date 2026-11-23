// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

/** Writes a session's steps to the run a worker opened for it. */
public record RunSteps(AgentRunRepository runs, String runId) implements AgentSessionRunner.StepSink {

    @Override
    public void session(String sessionId) {
        runs.attachSession(runId, sessionId);
    }

    @Override
    public void started(String callId, StepWords.Words w, long atMs) {
        runs.stepStarted(runId, callId, w.done(), w.doing(), atMs);
    }

    @Override
    public void ended(String callId, long atMs, boolean ok, StepWords.Words w) {
        runs.stepEnded(runId, callId, atMs, ok, ok ? w.done() : StepWords.failed(w));
    }

    @Override
    public void note(String words, long atMs) {
        runs.note(runId, words, atMs);
    }
}
