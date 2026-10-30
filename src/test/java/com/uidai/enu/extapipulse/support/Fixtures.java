package com.uidai.enu.extapipulse.support;

import com.uidai.enu.extapipulse.config.PulseProperties;
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
import com.uidai.enu.extapipulse.model.FailureCategory;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builders for config records, so tests stay readable. */
public final class Fixtures {

    public static final Set<FailureCategory> OUR_SIDE = EnumSet.of(
            FailureCategory.DNS, FailureCategory.PROXY, FailureCategory.NETWORK, FailureCategory.CONFIG_ERROR,
            FailureCategory.AUTH, FailureCategory.AUTH_REJECTED, FailureCategory.RATE_LIMITED);

    private Fixtures() {}

    /** Our-side categories -> infra@ours.com only; vendor categories -> dev@ours.com + API contacts. */
    public static Map<FailureCategory, RouteProps> routing() {
        Map<FailureCategory, RouteProps> m = new EnumMap<>(FailureCategory.class);
        for (FailureCategory c : FailureCategory.values()) {
            m.put(c, OUR_SIDE.contains(c)
                    ? new RouteProps(List.of("infra"), null, false, List.of("email"))
                    : new RouteProps(List.of("dev"), null, true, List.of("email")));
        }
        return m;
    }

    public static Map<String, List<String>> groups() {
        return Map.of("infra", List.of("infra@ours.com"), "dev", List.of("dev@ours.com"));
    }

    public static Alerting alerting(int failureThreshold, int recoveryThreshold, int reminderMin,
                                    double backoff, int maxReminderMin, int maxReminders) {
        return new Alerting(failureThreshold, recoveryThreshold, reminderMin, backoff, maxReminderMin, maxReminders, true);
    }

    public static Timing fastTiming() {
        return new Timing(2000, 3000, 60, 0, 10);
    }

    public static RequestProps get(String path) {
        return new RequestProps(null, path, "GET", null, null, null);
    }

    public static RequestProps post(String path, Map<String, String> headers, String body) {
        return new RequestProps(null, path, "POST", headers, null, body);
    }

    public static ExpectProps expectStatus(Integer... statuses) {
        return new ExpectProps(List.of(statuses), null, null, null, null, null);
    }

    public static ExpectProps expectJson(String path, String equals) {
        return new ExpectProps(List.of(200), path, equals, null, null, null);
    }

    public static DocProps doc(String docId, RequestProps request, ExpectProps expect) {
        return new DocProps(docId, null, null, null, null, request, expect, null, null, null, null);
    }

    public static DocProps doc(String docId, String auth, RequestProps request, ExpectProps expect) {
        return new DocProps(docId, null, auth, null, null, request, expect, null, null, null, null);
    }

    public static SourceProps source(String name, String baseUrl, String auth, List<String> contacts,
                                     DocProps... docs) {
        return new SourceProps(name, null, baseUrl, auth, null, contacts, null, null, null, List.of(docs));
    }

    public static AuthProfileProps tokenCall(String url, String body, Map<String, String> extract,
                                             Map<String, String> applyHeaders) {
        return new AuthProfileProps("token-call", null, null, null, null, null, null,
                new RequestProps(url, null, "POST", Map.of("Content-Type", "application/json"), null, body),
                null, extract, 600, false,
                applyHeaders == null ? null : new PulseProperties.ApplyProps(applyHeaders, null));
    }

    public static AuthProfileProps apiKey(String header, String value) {
        return new AuthProfileProps("apiKey", header, null, value, null, null, null,
                null, null, null, null, null, null);
    }

    public static PulseProperties props(List<SourceProps> sources,
                                        Map<String, AuthProfileProps> auth,
                                        Map<String, ControlCheckProps> control,
                                        Map<String, ProxyProps> proxies,
                                        Alerting alerting) {
        return new PulseProperties(false, fastTiming(), alerting,
                new PulseProperties.Mail(false, "pulse@ours.com", "[TEST]", "Asia/Kolkata"),
                proxies, control, auth, groups(), routing(), sources);
    }
}
