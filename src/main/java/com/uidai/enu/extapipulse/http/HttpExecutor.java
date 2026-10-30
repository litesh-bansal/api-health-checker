package com.uidai.enu.extapipulse.http;

import com.uidai.enu.extapipulse.template.ConfigException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Sends one {@link HttpCall} through the right client and times it. Network errors are thrown as-is for the classifier. */
public class HttpExecutor {

    /** Headers the JDK client sets itself and refuses to accept. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClientRegistry clients;

    public HttpExecutor(HttpClientRegistry clients) {
        this.clients = clients;
    }

    public HttpReply execute(HttpCall call, String proxyName) throws IOException, InterruptedException {
        HttpRequest request = build(call);
        long start = System.nanoTime();
        HttpResponse<String> response = clients.client(proxyName)
                .send(request, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return new HttpReply(response.statusCode(), response.headers(), response.body(), latencyMs);
    }

    static HttpRequest build(HttpCall call) {
        URI uri;
        try {
            uri = URI.create(call.url());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid URL after rendering: " + e.getMessage());
        }
        HttpRequest.BodyPublisher body = call.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(call.body());
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(call.timeout())
                .method(call.method(), body);
        for (Map.Entry<String, String> h : call.headers().entrySet()) {
            if (RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                throw new ConfigException("Header '" + h.getKey() + "' is set by the HTTP client and cannot be configured");
            }
            b.header(h.getKey(), h.getValue());
        }
        return b.build();
    }
}
