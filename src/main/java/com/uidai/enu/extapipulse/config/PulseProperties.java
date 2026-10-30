package com.uidai.enu.extapipulse.config;

import com.uidai.enu.extapipulse.model.FailureCategory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Raw configuration exactly as written in YAML under {@code pulse:}.
 * Every field is nullable here; {@link ProbeCatalog} applies defaults and inheritance
 * (global -> source -> docId) and validates the whole thing at startup.
 */
@ConfigurationProperties(prefix = "pulse")
public record PulseProperties(
        Boolean enabled,
        Timing defaults,
        Alerting alerting,
        Mail mail,
        Map<String, ProxyProps> proxies,
        Map<String, ControlCheckProps> controlChecks,
        Map<String, AuthProfileProps> authProfiles,
        Map<String, List<String>> recipientGroups,
        Map<FailureCategory, RouteProps> routing,
        List<SourceProps> sources
) {

    /** Request timing; usable at global, source and docId level. */
    public record Timing(
            Integer connectTimeoutMs,
            Integer timeoutMs,
            Integer intervalSec,
            Integer retries,
            Integer retryBackoffMs
    ) {}

    /** Alert throttling; usable at global, source and docId level. */
    public record Alerting(
            Integer failureThreshold,
            Integer recoveryThreshold,
            Integer reminderIntervalMin,
            Double reminderBackoff,
            Integer maxReminderIntervalMin,
            Integer maxReminders,
            Boolean sendRecoveryEmail
    ) {}

    public record Mail(
            Boolean enabled,
            String from,
            String subjectPrefix,
            String zone
    ) {}

    public record ProxyProps(
            String host,
            Integer port,
            String username,
            String password
    ) {}

    /** Keyed by proxy name ("none" = direct). Any HTTP response from any URL counts as reachable. */
    public record ControlCheckProps(
            List<String> urls,
            Integer timeoutMs,
            Integer cacheSec,
            Integer intervalSec
    ) {}

    public record AuthProfileProps(
            String type,              // none | apiKey | basic | bearer | token-call
            // apiKey
            String header,
            String queryParam,
            String value,
            // basic
            String username,
            String password,
            // bearer
            String token,
            // token-call
            RequestProps request,
            ExpectProps expect,
            Map<String, String> extract,
            Integer cacheTtlSec,
            Boolean alwaysRefresh,
            ApplyProps apply
    ) {}

    public record ApplyProps(
            Map<String, String> headers,
            Map<String, String> query
    ) {}

    public record RequestProps(
            String url,
            String path,
            String method,
            Map<String, String> headers,
            Map<String, String> query,
            String body
    ) {}

    public record ExpectProps(
            List<Integer> status,
            String jsonPath,
            String equals,
            Boolean exists,
            String bodyContains,
            Integer maxLatencyMs
    ) {}

    public record RouteProps(
            List<String> groups,
            List<String> emails,
            Boolean apiContacts,
            List<String> channels
    ) {}

    public record SourceProps(
            String source,
            Boolean enabled,
            String baseUrl,
            String auth,
            String proxy,
            List<String> contacts,
            Map<String, String> headers,
            Timing defaults,
            Alerting alerting,
            List<DocProps> docs
    ) {}

    public record DocProps(
            String docId,
            Boolean enabled,
            String auth,
            String proxy,
            List<String> contacts,
            RequestProps request,
            ExpectProps expect,
            Integer intervalSec,
            Integer timeoutMs,
            Integer retries,
            Alerting alerting
    ) {}
}
