package com.uidai.enu.extapipulse.config;

import com.uidai.enu.extapipulse.config.PulseProperties.Alerting;
import com.uidai.enu.extapipulse.config.PulseProperties.AuthProfileProps;
import com.uidai.enu.extapipulse.config.PulseProperties.ControlCheckProps;
import com.uidai.enu.extapipulse.config.PulseProperties.DocProps;
import com.uidai.enu.extapipulse.config.PulseProperties.ExpectProps;
import com.uidai.enu.extapipulse.config.PulseProperties.ProxyProps;
import com.uidai.enu.extapipulse.config.PulseProperties.RequestProps;
import com.uidai.enu.extapipulse.config.PulseProperties.RouteProps;
import com.uidai.enu.extapipulse.config.PulseProperties.SourceProps;
import com.uidai.enu.extapipulse.config.PulseProperties.Timing;
import com.uidai.enu.extapipulse.json.JsonPathLite;
import com.uidai.enu.extapipulse.model.AlertingPolicy;
import com.uidai.enu.extapipulse.model.AuthProfile;
import com.uidai.enu.extapipulse.model.AuthType;
import com.uidai.enu.extapipulse.model.Expectation;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.RequestTemplate;
import com.uidai.enu.extapipulse.model.Route;
import com.uidai.enu.extapipulse.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Turns raw {@link PulseProperties} into resolved, validated probe definitions.
 *
 * Inheritance: docId value -> source value -> global default -> built-in default.
 * Every mistake found is collected, and startup fails with the full list, so a typo
 * in config breaks the deploy instead of silently never alerting.
 */
public final class ProbeCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProbeCatalog.class);

    public static final String NONE = "none";
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    // Built-in defaults, used when config says nothing.
    static final int DEF_CONNECT_TIMEOUT_MS = 5_000;
    static final int DEF_TIMEOUT_MS = 10_000;
    static final int DEF_INTERVAL_SEC = 300;
    static final int DEF_RETRIES = 2;
    static final int DEF_RETRY_BACKOFF_MS = 2_000;
    static final int DEF_FAILURE_THRESHOLD = 3;
    static final int DEF_RECOVERY_THRESHOLD = 2;
    static final int DEF_REMINDER_MIN = 60;
    static final double DEF_BACKOFF = 1.0;
    static final int DEF_MAX_REMINDER_MIN = 480;
    static final int DEF_MAX_REMINDERS = 0; // 0 = keep reminding until it recovers
    static final int DEF_CONTROL_TIMEOUT_MS = 5_000;
    static final int DEF_CONTROL_CACHE_SEC = 30;
    static final int DEF_CONTROL_INTERVAL_SEC = 60;

    private final List<ProbeDefinition> probes = new ArrayList<>();
    private final Map<ProbeKey, ProbeDefinition> probesByKey = new LinkedHashMap<>();
    private final Map<String, AuthProfile> authProfiles = new LinkedHashMap<>();
    private final Map<String, List<String>> recipientGroups = new LinkedHashMap<>();
    private final Map<FailureCategory, Route> routing = new EnumMap<>(FailureCategory.class);
    private final Map<String, ResolvedProxy> proxies = new LinkedHashMap<>();
    private final Map<String, ControlCheck> controlChecks = new LinkedHashMap<>();
    private final AlertingPolicy globalAlerting;
    private final Duration connectTimeout;
    private final MailSettings mail;

    private final List<String> errors = new ArrayList<>();
    private final Function<String, String> envLookup;

    public record ResolvedProxy(String name, String host, int port, String username, String password) {}

    public record ControlCheck(String proxy, List<String> urls, Duration timeout, Duration cacheFor, Duration interval) {}

    public record MailSettings(boolean enabled, String from, String subjectPrefix, String zone) {}

    public ProbeCatalog(PulseProperties props, Function<String, String> envLookup) {
        this.envLookup = envLookup;
        Timing globalTiming = props.defaults();
        Alerting globalAlert = props.alerting();

        this.connectTimeout = Duration.ofMillis(firstNonNull(
                globalTiming == null ? null : globalTiming.connectTimeoutMs(), DEF_CONNECT_TIMEOUT_MS));
        this.globalAlerting = mergeAlerting(null, null, globalAlert);
        this.mail = resolveMail(props.mail());

        resolveProxies(nullSafe(props.proxies()));
        resolveRecipientGroups(nullSafe(props.recipientGroups()));
        resolveRouting(nullSafe(props.routing()));
        resolveAuthProfiles(nullSafe(props.authProfiles()));
        resolveSources(props.sources() == null ? List.of() : props.sources(), globalTiming, globalAlert);
        resolveControlChecks(nullSafe(props.controlChecks()));

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid pulse configuration:\n  - " + String.join("\n  - ", errors));
        }
        log.info("Loaded {} probe(s) across {} source(s), {} auth profile(s), {} control check(s)",
                probes.size(), probes.stream().map(p -> p.key().source()).distinct().count(),
                authProfiles.size(), controlChecks.size());
    }

    // ------------------------------------------------------------------ accessors

    public List<ProbeDefinition> probes() { return List.copyOf(probes); }

    public ProbeDefinition probe(ProbeKey key) { return probesByKey.get(key); }

    public AuthProfile authProfile(String name) {
        return name == null || NONE.equalsIgnoreCase(name) ? AuthProfile.none() : authProfiles.get(name);
    }

    public Map<String, List<String>> recipientGroups() { return recipientGroups; }

    public Route route(FailureCategory category) { return routing.get(category); }

    public Collection<ResolvedProxy> proxies() { return proxies.values(); }

    public ResolvedProxy proxy(String name) { return proxies.get(name); }

    public ControlCheck controlCheck(String proxyName) { return controlChecks.get(proxyName); }

    public Collection<ControlCheck> controlChecks() { return controlChecks.values(); }

    public AlertingPolicy globalAlerting() { return globalAlerting; }

    public Duration connectTimeout() { return connectTimeout; }

    public MailSettings mail() { return mail; }

    // ------------------------------------------------------------------ resolution

    private MailSettings resolveMail(PulseProperties.Mail m) {
        if (m == null) {
            return new MailSettings(false, "enu-external-api-pulse@localhost", "[ENU-PULSE]", "Asia/Kolkata");
        }
        return new MailSettings(
                Boolean.TRUE.equals(m.enabled()),
                firstNonNull(m.from(), "enu-external-api-pulse@localhost"),
                firstNonNull(m.subjectPrefix(), "[ENU-PULSE]"),
                firstNonNull(m.zone(), "Asia/Kolkata"));
    }

    private void resolveProxies(Map<String, ProxyProps> raw) {
        raw.forEach((name, p) -> {
            if (NONE.equalsIgnoreCase(name)) {
                errors.add("proxies: '" + name + "' is reserved (it means direct, no proxy)");
                return;
            }
            if (p == null || isBlank(p.host()) || p.port() == null) {
                errors.add("proxies." + name + ": host and port are required");
                return;
            }
            checkEnvRefs("proxies." + name + ".username", p.username());
            checkEnvRefs("proxies." + name + ".password", p.password());
            proxies.put(name, new ResolvedProxy(name, p.host(), p.port(), p.username(), p.password()));
        });
    }

    private void resolveRecipientGroups(Map<String, List<String>> raw) {
        raw.forEach((name, emails) -> {
            List<String> list = emails == null ? List.of() : List.copyOf(emails);
            list.forEach(e -> checkEmail("recipient-groups." + name, e));
            recipientGroups.put(name, list);
        });
    }

    /** Channel names are checked by AlertDispatcher, which knows which channel beans exist. */
    private void resolveRouting(Map<FailureCategory, RouteProps> raw) {
        for (FailureCategory category : FailureCategory.values()) {
            RouteProps r = raw.get(category);
            String where = "routing." + category;
            if (r == null) {
                errors.add(where + ": missing - every failure category needs a routing entry");
                continue;
            }
            List<String> groups = r.groups() == null ? List.of() : List.copyOf(r.groups());
            List<String> emails = r.emails() == null ? List.of() : List.copyOf(r.emails());
            List<String> channels = r.channels() == null || r.channels().isEmpty()
                    ? List.of("email") : List.copyOf(r.channels());
            boolean apiContacts = Boolean.TRUE.equals(r.apiContacts());

            groups.stream().filter(g -> !recipientGroups.containsKey(g))
                    .forEach(g -> errors.add(where + ": unknown recipient group '" + g + "'"));
            emails.forEach(e -> checkEmail(where, e));
            if (groups.isEmpty() && emails.isEmpty() && !apiContacts) {
                log.warn("{} has no recipients - failures of this kind will only be logged", where);
            }
            routing.put(category, new Route(groups, emails, apiContacts, channels));
        }
    }

    private void resolveAuthProfiles(Map<String, AuthProfileProps> raw) {
        raw.forEach((name, a) -> {
            String where = "auth-profiles." + name;
            if (NONE.equalsIgnoreCase(name)) {
                errors.add(where + ": 'none' is reserved (it means no auth)");
                return;
            }
            if (a == null) {
                errors.add(where + ": empty profile");
                return;
            }
            AuthType type;
            try {
                type = AuthType.parse(a.type());
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
                return;
            }
            Map<String, String> extract = a.extract() == null ? Map.of() : Map.copyOf(a.extract());
            Map<String, String> applyHeaders = a.apply() == null || a.apply().headers() == null
                    ? Map.of() : Map.copyOf(a.apply().headers());
            Map<String, String> applyQuery = a.apply() == null || a.apply().query() == null
                    ? Map.of() : Map.copyOf(a.apply().query());

            RequestTemplate request = null;
            Expectation expect = null;
            switch (type) {
                case NONE -> { }
                case API_KEY -> {
                    require(where, "value", a.value());
                    checkEnvRefs(where + ".value", a.value());
                }
                case BASIC -> {
                    require(where, "username", a.username());
                    require(where, "password", a.password());
                    checkEnvRefs(where + ".username", a.username());
                    checkEnvRefs(where + ".password", a.password());
                }
                case BEARER -> {
                    require(where, "token", a.token());
                    checkEnvRefs(where + ".token", a.token());
                }
                case TOKEN_CALL -> {
                    if (a.request() == null || isBlank(a.request().url())) {
                        errors.add(where + ".request.url: required for token-call (absolute URL)");
                    } else {
                        request = toTemplate(where + ".request", a.request(), null, Map.of());
                    }
                    expect = toExpectation(where + ".expect", a.expect());
                    if (extract.isEmpty()) {
                        errors.add(where + ".extract: token-call needs at least one value to extract");
                    }
                    extract.forEach((k, path) -> checkJsonPath(where + ".extract." + k, path));
                    Set<String> used = new LinkedHashSet<>();
                    Stream.concat(applyHeaders.values().stream(), applyQuery.values().stream())
                            .forEach(v -> used.addAll(TemplateRenderer.referencedKeys(v, "auth")));
                    used.stream().filter(k -> !extract.containsKey(k))
                            .forEach(k -> errors.add(where + ".apply: uses {{auth." + k + "}} but extract has no '" + k + "'"));
                }
            }
            applyHeaders.values().forEach(v -> checkEnvRefs(where + ".apply.headers", v));
            applyQuery.values().forEach(v -> checkEnvRefs(where + ".apply.query", v));

            Duration ttl = Duration.ofSeconds(firstNonNull(a.cacheTtlSec(), 300));
            String header = a.header();
            if (type == AuthType.API_KEY && isBlank(header) && isBlank(a.queryParam())) {
                header = "X-API-KEY";
            }
            authProfiles.put(name, new AuthProfile(name, type, header, a.queryParam(), a.value(),
                    a.username(), a.password(), a.token(), request, expect, extract, ttl,
                    Boolean.TRUE.equals(a.alwaysRefresh()), applyHeaders, applyQuery));
        });
    }

    private void resolveSources(List<SourceProps> sources, Timing globalTiming, Alerting globalAlert) {
        if (sources.isEmpty()) {
            log.warn("No sources configured - nothing will be probed");
        }
        Set<String> seenSources = new LinkedHashSet<>();
        for (SourceProps s : sources) {
            if (s == null || isBlank(s.source())) {
                errors.add("sources: an entry is missing 'source' (the state name)");
                continue;
            }
            String source = s.source().trim();
            if (!seenSources.add(source)) {
                errors.add("sources: '" + source + "' is defined twice - put all its docIds under one entry");
            }
            if (Boolean.FALSE.equals(s.enabled())) {
                log.info("Source {} is disabled", source);
                continue;
            }
            if (s.docs() == null || s.docs().isEmpty()) {
                errors.add("sources." + source + ": has no docs");
                continue;
            }
            for (DocProps d : s.docs()) {
                resolveDoc(source, s, d, globalTiming, globalAlert);
            }
        }
    }

    private void resolveDoc(String source, SourceProps s, DocProps d, Timing globalTiming, Alerting globalAlert) {
        if (d == null || isBlank(d.docId())) {
            errors.add("sources." + source + ".docs: an entry is missing 'docId'");
            return;
        }
        ProbeKey key = new ProbeKey(source, d.docId().trim());
        String where = "sources." + source + ".docs." + key.docId();
        if (probesByKey.containsKey(key)) {
            errors.add(where + ": duplicate (source, docId)");
            return;
        }
        if (Boolean.FALSE.equals(d.enabled())) {
            log.info("Probe {} is disabled", key);
            return;
        }
        if (d.request() == null) {
            errors.add(where + ".request: required");
            return;
        }

        String authName = firstNonNull(trimToNull(d.auth()), trimToNull(s.auth()), NONE);
        AuthProfile auth = authProfile(authName);
        if (auth == null) {
            errors.add(where + ": unknown auth profile '" + authName + "'");
        }

        String proxyName = firstNonNull(trimToNull(d.proxy()), trimToNull(s.proxy()), NONE);
        if (!NONE.equalsIgnoreCase(proxyName) && !proxies.containsKey(proxyName)) {
            errors.add(where + ": unknown proxy '" + proxyName + "'");
        }

        RequestTemplate request = toTemplate(where + ".request", d.request(), s.baseUrl(),
                s.headers() == null ? Map.of() : s.headers());

        // {{auth.X}} in the request must be something the auth profile extracts.
        if (request != null && auth != null) {
            Set<String> used = new LinkedHashSet<>(TemplateRenderer.referencedKeys(request.url(), "auth"));
            used.addAll(TemplateRenderer.referencedKeys(request.body(), "auth"));
            request.headers().values().forEach(v -> used.addAll(TemplateRenderer.referencedKeys(v, "auth")));
            request.query().values().forEach(v -> used.addAll(TemplateRenderer.referencedKeys(v, "auth")));
            for (String k : used) {
                if (auth.type() != AuthType.TOKEN_CALL || !auth.extract().containsKey(k)) {
                    errors.add(where + ": uses {{auth." + k + "}} but auth profile '" + authName + "' does not extract it");
                }
            }
        }

        List<String> contacts = d.contacts() != null && !d.contacts().isEmpty() ? List.copyOf(d.contacts())
                : s.contacts() != null ? List.copyOf(s.contacts()) : List.of();
        contacts.forEach(e -> checkEmail(where + ".contacts", e));

        Timing st = s.defaults();
        int interval = firstNonNull(d.intervalSec(), st == null ? null : st.intervalSec(),
                globalTiming == null ? null : globalTiming.intervalSec(), DEF_INTERVAL_SEC);
        int timeout = firstNonNull(d.timeoutMs(), st == null ? null : st.timeoutMs(),
                globalTiming == null ? null : globalTiming.timeoutMs(), DEF_TIMEOUT_MS);
        int retries = firstNonNull(d.retries(), st == null ? null : st.retries(),
                globalTiming == null ? null : globalTiming.retries(), DEF_RETRIES);
        int backoff = firstNonNull(st == null ? null : st.retryBackoffMs(),
                globalTiming == null ? null : globalTiming.retryBackoffMs(), DEF_RETRY_BACKOFF_MS);
        if (interval < 10) {
            errors.add(where + ": intervalSec must be at least 10");
        }
        if (retries < 0) {
            errors.add(where + ": retries cannot be negative");
        }

        if (request == null) {
            return; // errors already recorded
        }
        ProbeDefinition def = new ProbeDefinition(key, request, toExpectation(where + ".expect", d.expect()),
                authName, proxyName, contacts, Duration.ofSeconds(interval), Duration.ofMillis(timeout),
                Math.max(0, retries), Duration.ofMillis(backoff),
                mergeAlerting(d.alerting(), s.alerting(), globalAlert));
        probes.add(def);
        probesByKey.put(key, def);
    }

    private void resolveControlChecks(Map<String, ControlCheckProps> raw) {
        raw.forEach((proxyName, c) -> {
            String where = "control-checks." + proxyName;
            if (!NONE.equalsIgnoreCase(proxyName) && !proxies.containsKey(proxyName)) {
                errors.add(where + ": key must be 'none' or a proxy name defined under proxies");
                return;
            }
            if (c == null || c.urls() == null || c.urls().isEmpty()) {
                errors.add(where + ".urls: at least one reference URL is required");
                return;
            }
            c.urls().stream().filter(u -> !isHttpUrl(u))
                    .forEach(u -> errors.add(where + ".urls: not an http(s) URL: " + u));
            controlChecks.put(proxyName, new ControlCheck(proxyName, List.copyOf(c.urls()),
                    Duration.ofMillis(firstNonNull(c.timeoutMs(), DEF_CONTROL_TIMEOUT_MS)),
                    Duration.ofSeconds(firstNonNull(c.cacheSec(), DEF_CONTROL_CACHE_SEC)),
                    Duration.ofSeconds(firstNonNull(c.intervalSec(), DEF_CONTROL_INTERVAL_SEC))));
        });
        probes.stream().map(ProbeDefinition::proxy).distinct()
                .filter(p -> !controlChecks.containsKey(p))
                .forEach(p -> log.warn("No control check for proxy '{}' - failures through it cannot be told "
                        + "apart from our own network problems and will always be treated as the API's fault", p));
    }

    // ------------------------------------------------------------------ helpers

    private RequestTemplate toTemplate(String where, RequestProps r, String baseUrl, Map<String, String> baseHeaders) {
        String url;
        if (!isBlank(r.url())) {
            url = r.url().trim();
        } else if (!isBlank(baseUrl) && !isBlank(r.path())) {
            url = stripTrailingSlash(baseUrl.trim()) + (r.path().startsWith("/") ? "" : "/") + r.path().trim();
        } else {
            errors.add(where + ": needs 'url', or 'path' plus the source's 'baseUrl'");
            return null;
        }
        if (!isHttpUrl(url)) {
            errors.add(where + ": URL must start with http:// or https:// (got " + url + ")");
        }
        String method = firstNonNull(trimToNull(r.method()), "GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            errors.add(where + ".method: unsupported '" + method + "'");
        }
        Map<String, String> headers = mergeCaseInsensitive(baseHeaders, r.headers());
        Map<String, String> query = r.query() == null ? Map.of() : new LinkedHashMap<>(r.query());

        checkEnvRefs(where + ".url", url);
        checkEnvRefs(where + ".body", r.body());
        headers.forEach((k, v) -> checkEnvRefs(where + ".headers." + k, v));
        query.forEach((k, v) -> checkEnvRefs(where + ".query." + k, v));
        return new RequestTemplate(url, method, headers, query, r.body());
    }

    private Expectation toExpectation(String where, ExpectProps e) {
        if (e == null) {
            return Expectation.status200();
        }
        Set<Integer> statuses = e.status() == null || e.status().isEmpty()
                ? Set.of(200) : Set.copyOf(e.status());
        if (!isBlank(e.jsonPath())) {
            checkJsonPath(where + ".jsonPath", e.jsonPath());
        }
        if ((e.equals() != null || Boolean.TRUE.equals(e.exists())) && isBlank(e.jsonPath())) {
            errors.add(where + ": 'equals'/'exists' need a 'jsonPath'");
        }
        return new Expectation(statuses, trimToNull(e.jsonPath()), e.equals(),
                Boolean.TRUE.equals(e.exists()), e.bodyContains(),
                e.maxLatencyMs() == null ? null : e.maxLatencyMs().longValue());
    }

    private AlertingPolicy mergeAlerting(Alerting doc, Alerting src, Alerting global) {
        return new AlertingPolicy(
                firstNonNull(pick(doc, src, global, Alerting::failureThreshold), DEF_FAILURE_THRESHOLD),
                firstNonNull(pick(doc, src, global, Alerting::recoveryThreshold), DEF_RECOVERY_THRESHOLD),
                Duration.ofMinutes(firstNonNull(pick(doc, src, global, Alerting::reminderIntervalMin), DEF_REMINDER_MIN)),
                firstNonNull(pick(doc, src, global, Alerting::reminderBackoff), DEF_BACKOFF),
                Duration.ofMinutes(firstNonNull(pick(doc, src, global, Alerting::maxReminderIntervalMin), DEF_MAX_REMINDER_MIN)),
                firstNonNull(pick(doc, src, global, Alerting::maxReminders), DEF_MAX_REMINDERS),
                firstNonNull(pick(doc, src, global, Alerting::sendRecoveryEmail), Boolean.TRUE));
    }

    private static <T> T pick(Alerting doc, Alerting src, Alerting global, Function<Alerting, T> getter) {
        for (Alerting a : new Alerting[]{doc, src, global}) {
            if (a != null && getter.apply(a) != null) {
                return getter.apply(a);
            }
        }
        return null;
    }

    private void checkEnvRefs(String where, String template) {
        for (String key : TemplateRenderer.referencedKeys(template, "env")) {
            if (envLookup.apply(key) == null) {
                errors.add(where + ": {{env." + key + "}} is not set (environment variable or property '" + key + "')");
            }
        }
    }

    private void checkJsonPath(String where, String path) {
        try {
            JsonPathLite.validate(path);
        } catch (IllegalArgumentException e) {
            errors.add(where + ": " + e.getMessage());
        }
    }

    private void checkEmail(String where, String email) {
        if (email == null || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add(where + ": not a valid email address: " + email);
        }
    }

    private void require(String where, String field, String value) {
        if (isBlank(value)) {
            errors.add(where + "." + field + ": required");
        }
    }

    private static Map<String, String> mergeCaseInsensitive(Map<String, String> base, Map<String, String> override) {
        Map<String, String> out = new LinkedHashMap<>();
        Stream.of(base, override).filter(Objects::nonNull).forEach(m -> m.forEach((k, v) -> {
            out.keySet().removeIf(existing -> existing.equalsIgnoreCase(k));
            out.put(k, v);
        }));
        return out;
    }

    private static boolean isHttpUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static <K, V> Map<K, V> nullSafe(Map<K, V> m) {
        return m == null ? Map.of() : m;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
