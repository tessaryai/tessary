// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.usage.LlmUsageAccountant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Long-lived agent sessions on the launcher ({@code sandbox-runner/README.md}, "Launcher sessions"):
 * one sandbox per session that takes many messages and streams numbered events. Generic: the caller
 * brings the prompt, the files, the MCP servers and their tokens, and the subject its spend is booked
 * on. The credential is claimed per reply, not per session, and each reply's usage is booked once.
 */
public interface AgentSessionClient {

    /**
     * Starts the sandbox and the agent on the lane's model, with the credential held only until the
     * launcher answered.
     *
     * @throws ai.tessary.open.errors.TessaryException {@code AgentRunError.*} for every way the start
     *     can fail ({@code CAPACITY} at the launcher's session cap), or the resolver's
     *     {@code ModelConfigError} when the org holds no credential for the lane's provider
     */
    Session start(Start start);

    /**
     * One message. The reply's credential is claimed here and held until the reply's
     * {@code message_end}, an abort or the session's close.
     *
     * @return {@code ACCEPTED}, {@code BUSY} while a reply runs, {@code CLOSED} when the session is gone
     */
    SendOutcome send(String sessionId, String text);

    /** Ends the running reply and keeps the session; its {@code message_end} has reason {@code ABORTED}. Quiet on a closed session. */
    void abort(String sessionId);

    /** Stops the session and its sandbox. Quiet on a closed session. */
    void stop(String sessionId);

    /**
     * The session's events after {@code afterSeq}, in order, as they come; blocking, so read it on a
     * virtual thread and close it. The stream ends after {@link SessionEvent.Closed}, or with no
     * {@code Closed} when the connection ends: then resume from the last sequence read. A session the
     * launcher no longer knows yields one {@code Closed(afterSeq, UNKNOWN)}.
     *
     * @throws ai.tessary.open.errors.TessaryException {@code AgentRunError.*} when the launcher refuses
     *     the stream ({@code RUN_FAILED} for an {@code afterSeq} it no longer holds), and
     *     {@code BAD_OUTPUT} from the stream for a frame that does not parse
     */
    Stream<SessionEvent> events(String sessionId, long afterSeq);

    /**
     * What a session starts with.
     *
     * @param lane the lane the session runs and books on
     * @param systemPrompt the agent's system prompt
     * @param files relative path to content, written under the sandbox's {@code dossier/}
     * @param mcpServers the named MCP servers the agent reaches; their tokens go to the launcher only
     * @param idleTimeoutMs the launcher closes the session after this long with no message; no default
     * @param maxToolCallsPerMessage the agent's step cap per reply
     * @param subject what the spend is for
     */
    record Start(
            String projectId,
            ModelLane lane,
            String systemPrompt,
            Map<String, String> files,
            List<McpServer> mcpServers,
            long idleTimeoutMs,
            int maxToolCallsPerMessage,
            LlmUsageAccountant.Subject subject) {}

    /**
     * One named MCP server the agent reaches through the sandbox's relay.
     *
     * @param token a project key the caller owns and revokes; never logged, never in {@code toString}
     */
    record McpServer(String name, String url, String token) {
        @Override
        public String toString() {
            return "McpServer[name=" + name + ", url=" + url + "]";
        }
    }

    /**
     * A started session.
     *
     * @param id the launcher's session id
     */
    record Session(String id) {}

    enum SendOutcome {
        ACCEPTED,
        BUSY,
        CLOSED
    }
}
