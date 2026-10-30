package com.uidai.enu.extapipulse.http;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.config.ProbeCatalog.ResolvedProxy;
import com.uidai.enu.extapipulse.template.TemplateRenderer;
import com.uidai.enu.extapipulse.template.TemplateRenderer.Escape;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One {@link HttpClient} per route: "none" (direct) plus one per named proxy.
 * Redirects are not followed, so a moved endpoint shows up as a status we can see.
 */
public class HttpClientRegistry implements AutoCloseable {

    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HttpClientRegistry(ProbeCatalog catalog, TemplateRenderer renderer) {
        Duration connectTimeout = catalog.connectTimeout();
        clients.put(ProbeCatalog.NONE, baseBuilder(connectTimeout)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build());
        for (ResolvedProxy p : catalog.proxies()) {
            HttpClient.Builder b = baseBuilder(connectTimeout)
                    .proxy(ProxySelector.of(new InetSocketAddress(p.host(), p.port())));
            if (p.username() != null && !p.username().isBlank()) {
                String user = renderer.render(p.username(), Map.of(), Escape.NONE);
                String pass = renderer.render(p.password() == null ? "" : p.password(), Map.of(), Escape.NONE);
                renderer.masker().register(pass);
                b.authenticator(new ProxyOnlyAuthenticator(user, pass.toCharArray()));
            }
            clients.put(p.name(), b.build());
        }
    }

    public HttpClient client(String proxyName) {
        HttpClient c = clients.get(proxyName == null ? ProbeCatalog.NONE : proxyName);
        if (c == null) {
            throw new IllegalArgumentException("No HTTP client for proxy '" + proxyName + "'");
        }
        return c;
    }

    private HttpClient.Builder baseBuilder(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)   // many state servers mishandle HTTP/2 upgrade
                .executor(executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** Answers only proxy challenges, so proxy credentials are never sent to a vendor. */
    private static final class ProxyOnlyAuthenticator extends Authenticator {
        private final String user;
        private final char[] password;

        ProxyOnlyAuthenticator(String user, char[] password) {
            this.user = user;
            this.password = password;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            return getRequestorType() == RequestorType.PROXY
                    ? new PasswordAuthentication(user, password)
                    : null;
        }
    }
}
