package com.uidai.enu.extapipulse.auth;

import tools.jackson.databind.JsonNode;
import com.uidai.enu.extapipulse.classify.ExpectationCheck;
import com.uidai.enu.extapipulse.http.CallBuilder;
import com.uidai.enu.extapipulse.http.HttpCall;
import com.uidai.enu.extapipulse.http.HttpExecutor;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.json.JsonPathLite;
import com.uidai.enu.extapipulse.model.AuthProfile;
import com.uidai.enu.extapipulse.template.TemplateRenderer;
import com.uidai.enu.extapipulse.template.TemplateRenderer.Escape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Produces what the main request needs from an auth profile.
 * For token-call profiles this means running the login request, checking it, pulling values out
 * of its JSON response and caching them. Probes sharing a profile share one login.
 */
public class AuthResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthResolver.class);

    /** Refresh this long before the vendor's stated expiry. */
    private static final Duration EXPIRY_SAFETY = Duration.ofSeconds(30);
    private static final String EXPIRES_IN_VAR = "expiresIn";

    private final HttpExecutor executor;
    private final CallBuilder callBuilder;
    private final TemplateRenderer renderer;
    private final ExpectationCheck expectationCheck;
    private final TokenCache cache;
    private final Clock clock;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AuthResolver(HttpExecutor executor, CallBuilder callBuilder, TemplateRenderer renderer,
                        ExpectationCheck expectationCheck, TokenCache cache, Clock clock) {
        this.executor = executor;
        this.callBuilder = callBuilder;
        this.renderer = renderer;
        this.expectationCheck = expectationCheck;
        this.cache = cache;
        this.clock = clock;
    }

    public AuthContext resolve(AuthProfile profile, String proxy, Duration timeout, boolean forceRefresh)
            throws IOException, InterruptedException, AuthStepException {
        return switch (profile.type()) {
            case NONE -> AuthContext.empty();
            case API_KEY -> apiKey(profile);
            case BASIC -> basic(profile);
            case BEARER -> header("Authorization", "Bearer " + render(profile.token()));
            case TOKEN_CALL -> tokenCall(profile, proxy, timeout, forceRefresh);
        };
    }

    /** Forget a cached login, e.g. after the main call got 401 with it. */
    public void invalidate(AuthProfile profile, String proxy) {
        cache.invalidate(cacheKey(profile, proxy));
    }

    private AuthContext apiKey(AuthProfile p) {
        String value = render(p.value());
        if (p.queryParam() != null && !p.queryParam().isBlank()) {
            return new AuthContext(Map.of(), Map.of(), Map.of(p.queryParam(), value), false);
        }
        return header(p.header(), value);
    }

    private AuthContext basic(AuthProfile p) {
        String raw = render(p.username()) + ":" + render(p.password());
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        renderer.masker().register(encoded);
        return header("Authorization", "Basic " + encoded);
    }

    private AuthContext tokenCall(AuthProfile p, String proxy, Duration timeout, boolean forceRefresh)
            throws IOException, InterruptedException, AuthStepException {
        String key = cacheKey(p, proxy);
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            if (!forceRefresh && !p.alwaysRefresh()) {
                Optional<Map<String, String>> cached = cache.get(key);
                if (cached.isPresent()) {
                    return apply(p, cached.get(), true);
                }
            }
            Map<String, String> vars = login(p, proxy, timeout);
            cache.put(key, vars, expiry(p, vars));
            return apply(p, vars, false);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> login(AuthProfile p, String proxy, Duration timeout)
            throws IOException, InterruptedException, AuthStepException {
        HttpCall call = callBuilder.build(p.request(), AuthContext.empty(), timeout);
        HttpReply reply = executor.execute(call, proxy);

        Optional<ExpectationCheck.Mismatch> mismatch = expectationCheck.check(reply, p.expect());
        if (mismatch.isPresent()) {
            throw new AuthStepException(AuthStepException.Kind.UNEXPECTED_RESPONSE, reply,
                    "Login call to " + p.request().url() + ": " + mismatch.get().reason());
        }

        JsonNode root;
        try {
            root = expectationCheck.parse(reply.body());
        } catch (Exception e) {
            throw new AuthStepException(AuthStepException.Kind.EXTRACT_FAILED, reply,
                    "Login call returned " + reply.status() + " but the body is not JSON");
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.extract().entrySet()) {
            Optional<String> value = JsonPathLite.readText(root, e.getValue());
            if (value.isEmpty() || value.get().isBlank()) {
                throw new AuthStepException(AuthStepException.Kind.EXTRACT_FAILED, reply,
                        "Login call returned " + reply.status() + " but '" + e.getValue()
                                + "' (for " + e.getKey() + ") is not in the response");
            }
            renderer.masker().register(value.get());
            vars.put(e.getKey(), value.get());
        }
        log.debug("Logged in for auth profile {} via proxy {}", p.name(), proxy);
        return vars;
    }

    private Instant expiry(AuthProfile p, Map<String, String> vars) {
        Duration ttl = p.cacheTtl();
        String expiresIn = vars.get(EXPIRES_IN_VAR);
        if (expiresIn != null) {
            try {
                Duration stated = Duration.ofSeconds(Long.parseLong(expiresIn.trim())).minus(EXPIRY_SAFETY);
                ttl = stated.compareTo(Duration.ofSeconds(10)) < 0 ? Duration.ofSeconds(10) : stated;
            } catch (NumberFormatException e) {
                log.warn("Auth profile {}: expiresIn '{}' is not a number of seconds; using cacheTtlSec", p.name(), expiresIn);
            }
        }
        return clock.instant().plus(ttl);
    }

    private AuthContext apply(AuthProfile p, Map<String, String> vars, boolean fromCache) {
        Map<String, String> headers = new LinkedHashMap<>();
        p.applyHeaders().forEach((k, v) -> headers.put(k, renderer.render(v, vars, Escape.NONE)));
        Map<String, String> query = new LinkedHashMap<>();
        p.applyQuery().forEach((k, v) -> query.put(k, renderer.render(v, vars, Escape.NONE)));
        return new AuthContext(vars, headers, query, fromCache);
    }

    private AuthContext header(String name, String value) {
        return new AuthContext(Map.of(), Map.of(name, value), Map.of(), false);
    }

    private String render(String template) {
        String value = renderer.render(template, Map.of(), Escape.NONE);
        renderer.masker().register(value);
        return value;
    }

    private static String cacheKey(AuthProfile p, String proxy) {
        return p.name() + "@" + proxy;
    }
}
