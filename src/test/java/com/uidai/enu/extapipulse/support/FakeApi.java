package com.uidai.enu.extapipulse.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** A tiny local HTTP server whose answers each test scripts per path. JDK only, no extra dependency. */
public class FakeApi implements AutoCloseable {

    public record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().get(0)).orElse(null);
        }
    }

    public record Response(int status, String body, long delayMs) {
        public static Response json(int status, String body) {
            return new Response(status, body, 0);
        }

        public static Response slow(int status, String body, long delayMs) {
            return new Response(status, body, delayMs);
        }
    }

    private final HttpServer server;
    private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void on(String path, Function<Request, Response> handler) {
        handlers.put(path, handler);
    }

    public void on(String path, int status, String body) {
        on(path, r -> Response.json(status, body));
    }

    public int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    public List<Request> requests() {
        return requests;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Request req = new Request(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(),
                Map.copyOf(ex.getRequestHeaders()), body);
        requests.add(req);
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();

        Function<Request, Response> h = handlers.get(path);
        Response resp = h == null ? Response.json(404, "{\"error\":\"no handler\"}") : h.apply(req);
        if (resp.delayMs() > 0) {
            try {
                Thread.sleep(resp.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = resp.body() == null ? new byte[0] : resp.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        try {
            ex.sendResponseHeaders(resp.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            }
        } catch (IOException ignored) {
            // client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
