// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.jspecify.annotations.Nullable;

/**
 * A loopback launcher on a bare socket (forbidden-apis bans {@code com.sun.net.httpserver}): scripted
 * JSON answers per route, and event streams sent the way node sends them, as a chunked
 * {@code text/event-stream} body that ends cleanly, is cut mid-stream, or stays open while the test
 * pushes frames. Every connection gets its own virtual thread, so a held stream never blocks a POST.
 * An unscripted route answers {@code 418}, which every client call turns into an exception.
 */
final class FakeLauncher implements AutoCloseable {

    /** One request as the launcher saw it; header names are lower-case. */
    record Seen(String method, String path, Map<String, String> headers, String body) {}

    /** {@code CUT} closes the connection after the frames are flushed, without the last chunk. */
    enum Ending {
        END,
        CUT
    }

    /** A stream the test feeds while the client reads it. */
    interface LiveEvents {
        void push(String frame);

        void end();
    }

    private sealed interface Answer {}

    private record Json(int status, @Nullable String body) implements Answer {}

    private record Frames(Ending ending, List<String> frames) implements Answer {}

    private record Live(BlockingQueue<Optional<String>> queue) implements Answer, LiveEvents {
        @Override
        public void push(String frame) {
            queue.add(Optional.of(frame));
        }

        @Override
        public void end() {
            queue.add(Optional.empty());
        }
    }

    private final ServerSocket socket;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Answer>> routes = new ConcurrentHashMap<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final List<Live> lives = new CopyOnWriteArrayList<>();

    private FakeLauncher() throws IOException {
        socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(this::accept, "fake-launcher");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    static FakeLauncher start() throws IOException {
        return new FakeLauncher();
    }

    String url() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    /** {@code method} and {@code path} (with its query) answer {@code status} and {@code json}; the last answer repeats. */
    FakeLauncher answer(String method, String path, int status, @Nullable String json) {
        return add(method, path, new Json(status, json));
    }

    /** {@code GET path} answers 200 with these frames as chunks, then ends the body or cuts the socket. */
    FakeLauncher events(String path, Ending ending, String... frames) {
        return add("GET", path, new Frames(ending, List.of(frames)));
    }

    /** {@code GET path} answers 200 and stays open; the test pushes frames and ends it. */
    LiveEvents liveEvents(String path) {
        Live live = new Live(new LinkedBlockingQueue<>());
        lives.add(live);
        add("GET", path, live);
        return live;
    }

    static String frame(long seq, String type, String json) {
        return "id: " + seq + "\nevent: " + type + "\ndata: " + json + "\n\n";
    }

    private FakeLauncher add(String method, String path, Answer answer) {
        routes.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).addLast(answer);
        return this;
    }

    private @Nullable Answer next(String method, String path) {
        Deque<Answer> answers = routes.get(method + " " + path);
        if (answers == null) return null;
        synchronized (answers) {
            return answers.size() > 1 ? answers.pollFirst() : answers.peekFirst();
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        for (Live live : lives) live.end();
        for (Socket s : open) s.close();
    }

    private void accept() {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return;
            }
            open.add(client);
            Thread.ofVirtual().name("fake-launcher-conn").start(() -> serve(client));
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            String[] head = readHead(in).split("\r\n", -1);
            String[] line = head[0].split(" ", -1);
            if (line.length < 2) return;
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < head.length; i++) {
                int colon = head[i].indexOf(':');
                if (colon > 0) {
                    headers.put(
                            head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            head[i].substring(colon + 1).trim());
                }
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
            seen.add(new Seen(line[0], line[1], Map.copyOf(headers), body));
            OutputStream out = client.getOutputStream();
            Answer answer = next(line[0], line[1]);
            switch (answer) {
                case null -> json(out, 418, "{\"error\":\"unscripted\"}");
                case Json j -> json(out, j.status(), j.body());
                case Frames f -> {
                    streamHead(out);
                    for (String frame : f.frames()) chunk(out, frame);
                    if (f.ending() == Ending.CUT) {
                        out.flush();
                        return;
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                case Live l -> {
                    streamHead(out);
                    while (true) {
                        Optional<String> frame = l.queue().take();
                        if (frame.isEmpty()) break;
                        chunk(out, frame.get());
                    }
                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } catch (IOException e) {
            // the client hung up or the test closed the launcher; nothing is left to answer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            open.remove(client);
        }
    }

    private static void json(OutputStream out, int status, @Nullable String body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String type = body == null ? "" : "Content-Type: application/json\r\n";
        out.write(("HTTP/1.1 " + status + " Fake\r\n" + type + "Content-Length: " + payload.length
                        + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    private static void streamHead(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
                        + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void chunk(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int p3 = -1;
        int p2 = -1;
        int p1 = -1;
        int c;
        while ((c = in.read()) != -1) {
            head.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') break;
            p3 = p2;
            p2 = p1;
            p1 = c;
        }
        return head.toString(StandardCharsets.UTF_8);
    }
}
