// SPDX-License-Identifier: Apache-2.0
package ai.tessary.fix;

import ai.tessary.agentrun.AgentSessionClient;
import ai.tessary.agentrun.SessionEvent;
import ai.tessary.sandbox.AgentSpanTelemetry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The launcher's fix sessions, one scripted reply each: the agent's answer, then the patch of what it changed, built on
 * the commit its clone checked out. That commit comes from {@link FakeGithub}: the head sha the session was started
 * at, or the head of the branch it was started on, as the sandbox's clone would read it.
 */
final class FakeFixSessions implements AgentSessionClient {

    /** One agent reply: its JSON answer and the files it wrote, path to new content. */
    record Reply(String answerJson, Map<String, String> files) {}

    private final FakeGithub github;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Start> starts = new ArrayList<>();
    private final Map<String, List<SessionEvent>> events = new HashMap<>();
    private final Map<String, String> baseShas = new HashMap<>();
    private int sessions;

    FakeFixSessions(FakeGithub github) {
        this.github = github;
    }

    synchronized void willReply(Reply reply) {
        replies.add(reply);
    }

    synchronized List<Start> starts() {
        return List.copyOf(starts);
    }

    @Override
    public synchronized Session start(Start start) {
        starts.add(start);
        Clone repo = Objects.requireNonNull(start.repo(), "a fix session always clones");
        String id = "session-" + ++sessions;
        events.put(id, new ArrayList<>());
        baseShas.put(id, repo.headSha() != null ? repo.headSha() : github.head(Objects.requireNonNull(repo.branch())));
        return new Session(id);
    }

    @Override
    public synchronized SendOutcome send(String sessionId, String text) {
        List<SessionEvent> out = events.get(sessionId);
        Reply reply = replies.poll();
        if (out == null || reply == null) throw new AssertionError("no reply scripted for " + sessionId);
        List<SessionEvent.PatchFile> files = new ArrayList<>();
        for (Map.Entry<String, String> f : reply.files().entrySet()) {
            files.add(new SessionEvent.PatchFile(
                    f.getKey(),
                    "100644",
                    Base64.getEncoder().encodeToString(f.getValue().getBytes(StandardCharsets.UTF_8))));
        }
        out.add(new SessionEvent.Text(out.size() + 1, reply.answerJson()));
        out.add(new SessionEvent.Patch(
                out.size() + 1, Objects.requireNonNull(baseShas.get(sessionId)), files, List.of(), null));
        out.add(new SessionEvent.MessageEnd(
                out.size() + 1,
                new AgentSpanTelemetry.AgentUsage(100, 50, 0, 0, BigDecimal.ONE),
                SessionEvent.EndReason.DONE));
        return SendOutcome.ACCEPTED;
    }

    @Override
    public synchronized void abort(String sessionId) {}

    @Override
    public synchronized void stop(String sessionId) {
        List<SessionEvent> out = events.get(sessionId);
        if (out != null && (out.isEmpty() || !(out.getLast() instanceof SessionEvent.Closed))) {
            out.add(new SessionEvent.Closed(out.size() + 1, SessionEvent.CloseReason.STOPPED));
        }
    }

    @Override
    public synchronized Stream<SessionEvent> events(String sessionId, long afterSeq) {
        List<SessionEvent> out = events.getOrDefault(sessionId, List.of());
        return out.stream().filter(e -> e.seq() > afterSeq).toList().stream();
    }
}
