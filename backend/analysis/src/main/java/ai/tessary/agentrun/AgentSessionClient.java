// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.usage.LlmUsageAccountant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Long-lived agent sessions on the launcher ({@code sandbox-runner/README.md}). The credential is claimed per reply,
 * not per session, and each reply's usage is booked once.
 */
public interface AgentSessionClient {

    /**
     * The credential is held only until the launcher answers. @throws ai.tessary.open.errors.TessaryException
     * {@code AgentRunError.*} ({@code CAPACITY} at the launcher's session cap), or {@code ModelConfigError} when the
     * org holds no credential for the lane's provider
     */
    Session start(Start start);

    /**
     * The reply's credential is held until its {@code message_end}, an abort or the session's close. @return
     * {@code ACCEPTED}, {@code BUSY} while a reply runs, {@code CLOSED} when the session is gone
     */
    SendOutcome send(String sessionId, String text);

    /** Ends the running reply and keeps the session; its {@code message_end} has reason {@code ABORTED}. Quiet on a closed session. */
    void abort(String sessionId);

    /** Stops the session and its sandbox. Quiet on a closed session. */
    void stop(String sessionId);

    /**
     * Blocking: read it on a virtual thread and close it. If the stream ends without {@link SessionEvent.Closed},
     * resume from the last sequence read; an unknown session yields one {@code Closed(afterSeq, UNKNOWN)}. @throws
     * ai.tessary.open.errors.TessaryException {@code RUN_FAILED} for an {@code afterSeq} the launcher no longer holds,
     * {@code BAD_OUTPUT} for an unparseable frame
     */
    Stream<SessionEvent> events(String sessionId, long afterSeq);

    /**
     * @param systemPrompt null only on the RCA lane, which runs the agent's default prompt @param files relative path to
     * content, written under the sandbox's {@code dossier/} @param mcpServers their tokens go to the launcher only
     * @param idleTimeoutMs the launcher closes the session after this long with no message; no default @param
     * maxToolCallsPerMessage the agent's step cap per reply @param repo the repository the sandbox clones before it is
     * ready, RCA only
     */
    record Start(
            String projectId,
            ModelLane lane,
            @Nullable String systemPrompt,
            Map<String, String> files,
            List<McpServer> mcpServers,
            long idleTimeoutMs,
            int maxToolCallsPerMessage,
            LlmUsageAccountant.Subject subject,
            @Nullable Clone repo) {

        public Start(
                String projectId,
                ModelLane lane,
                @Nullable String systemPrompt,
                Map<String, String> files,
                List<McpServer> mcpServers,
                long idleTimeoutMs,
                int maxToolCallsPerMessage,
                LlmUsageAccountant.Subject subject) {
            this(
                    projectId,
                    lane,
                    systemPrompt,
                    files,
                    mcpServers,
                    idleTimeoutMs,
                    maxToolCallsPerMessage,
                    subject,
                    null);
        }
    }

    /**
     * @param url embeds a short-lived token: never logged, never in {@code toString} @param onsetAt when the movement
     * began, so the sandbox can name the commit live then
     */
    record Clone(String url, String headSha, @Nullable String onsetAt) {
        @Override
        public String toString() {
            return "Clone[headSha=" + headSha + ", onsetAt=" + onsetAt + "]";
        }
    }

    /** @param token a project key the caller owns and revokes; never logged, never in {@code toString} */
    record McpServer(String name, String url, String token) {
        @Override
        public String toString() {
            return "McpServer[name=" + name + ", url=" + url + "]";
        }
    }

    record Session(String id) {}

    enum SendOutcome {
        ACCEPTED,
        BUSY,
        CLOSED
    }
}
