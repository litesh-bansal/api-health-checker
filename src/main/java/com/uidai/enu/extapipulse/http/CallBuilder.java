package com.uidai.enu.extapipulse.http;

import com.uidai.enu.extapipulse.auth.AuthContext;
import com.uidai.enu.extapipulse.model.RequestTemplate;
import com.uidai.enu.extapipulse.template.TemplateRenderer;
import com.uidai.enu.extapipulse.template.TemplateRenderer.Escape;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Renders a {@link RequestTemplate} plus whatever auth adds into a ready-to-send {@link HttpCall}. */
public class CallBuilder {

    private final TemplateRenderer renderer;

    public CallBuilder(TemplateRenderer renderer) {
        this.renderer = renderer;
    }

    public HttpCall build(RequestTemplate t, AuthContext auth, Duration timeout) {
        Map<String, String> vars = auth.vars();

        Map<String, String> headers = new LinkedHashMap<>();
        t.headers().forEach((k, v) -> headers.put(k, renderer.render(v, vars, Escape.NONE)));
        auth.headers().forEach((k, v) -> {
            headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(k));
            headers.put(k, v);
        });

        Map<String, String> query = new LinkedHashMap<>();
        t.query().forEach((k, v) -> query.put(k, renderer.render(v, vars, Escape.NONE)));
        query.putAll(auth.query());

        String url = renderer.render(t.url(), vars, Escape.NONE);
        if (!query.isEmpty()) {
            StringJoiner qs = new StringJoiner("&");
            query.forEach((k, v) -> qs.add(enc(k) + "=" + enc(v)));
            url = url + (url.contains("?") ? "&" : "?") + qs;
        }

        String body = renderer.render(t.body(), vars, TemplateRenderer.escapeForContentType(t.headers()));
        return new HttpCall(t.method(), url, headers, body, timeout);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
