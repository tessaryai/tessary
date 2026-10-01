// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LoopbackHttpStub implements AutoCloseable {

    public record Request(String line, Map<String, String> headers, String body) {
        public String path() {
            return line.split(" ", -1)[1];
        }
    }

    private final ServerSocket socket;
    private final Thread acceptor;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String body;

    private LoopbackHttpStub(int status, String body) throws IOException {
        this.status = status;
        this.body = body;
        this.socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        this.acceptor = new Thread(this::serve, "loopback-http-stub");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public static LoopbackHttpStub answering(int status, String body) throws IOException {
        return new LoopbackHttpStub(status, body);
    }

    public void answer(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    public List<Request> requests() {
        return requests;
    }

    public Request lastRequest() {
        return requests.getLast();
    }

    @Override
    public void close() throws IOException {
        Thread.interrupted();
        socket.close();
        try {
            acceptor.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                InputStream in = client.getInputStream();
                String[] head = readHead(in).split("\r\n", -1);
                Map<String, String> headers = new LinkedHashMap<>();
                for (int i = 1; i < head.length; i++) {
                    int colon = head[i].indexOf(':');
                    if (colon > 0) {
                        headers.put(
                                head[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                head[i].substring(colon + 1).trim());
                    }
                }
                int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                String sentBody = new String(in.readNBytes(contentLength), StandardCharsets.UTF_8);
                requests.add(new Request(head[0], Map.copyOf(headers), sentBody));
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = client.getOutputStream();
                out.write(("HTTP/1.1 " + status + " Stub\r\nContent-Type: application/json\r\nContent-Length: "
                                + payload.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
            } catch (IOException | RuntimeException e) {
                return;
            }
        }
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
