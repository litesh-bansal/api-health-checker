package com.uidai.enu.extapipulse.alert;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.incident.AlertEvent;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.Stage;
import com.uidai.enu.extapipulse.template.SecretMasker;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

/** Builds the subject and plain-text body for alert emails. All output passes through the secret masker. */
public class AlertFormatter {

    private static final Map<FailureCategory, String> ACTION = new EnumMap<>(FailureCategory.class);

    static {
        ACTION.put(FailureCategory.DNS, "Check DNS resolution from the monitoring server.");
        ACTION.put(FailureCategory.PROXY, "Check that the proxy is up, its credentials, and that the API host is whitelisted.");
        ACTION.put(FailureCategory.NETWORK, "Check firewall rules and routes to the API host. If our side looks fine, the API host itself may be unreachable.");
        ACTION.put(FailureCategory.CONFIG_ERROR, "Fix the probe configuration (missing secret or wrong placeholder).");
        ACTION.put(FailureCategory.TLS, "Check the API's certificate (expiry, chain) and our truststore.");
        ACTION.put(FailureCategory.AUTH, "Check our API credentials and IP whitelisting with the provider.");
        ACTION.put(FailureCategory.AUTH_REJECTED, "The login was rejected. Check whether our credentials expired or were rotated.");
        ACTION.put(FailureCategory.AUTH_ENDPOINT_DOWN, "The provider's login service is failing. Please investigate on your side.");
        ACTION.put(FailureCategory.AUTH_EXTRACT_FAILED, "The login response no longer contains the token field. Please confirm whether the login API changed.");
        ACTION.put(FailureCategory.VENDOR_DOWN, "Please investigate the API service on your side.");
        ACTION.put(FailureCategory.RATE_LIMITED, "We are being rate limited. Check our quota or reduce the probe frequency.");
        ACTION.put(FailureCategory.SLOW_RESPONSE, "The API is responding slowly. Please investigate performance on your side.");
        ACTION.put(FailureCategory.CONTRACT, "The API answered differently from what we expect. Please confirm whether it changed.");
    }

    private final ProbeCatalog catalog;
    private final SecretMasker masker;
    private final ZoneId zone;
    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter time;

    public AlertFormatter(ProbeCatalog catalog, SecretMasker masker) {
        this.catalog = catalog;
        this.masker = masker;
        this.zone = ZoneId.of(catalog.mail().zone());
        this.dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(zone);
        this.time = DateTimeFormatter.ofPattern("HH:mm z").withZone(zone);
    }

    public String subject(AlertEvent e) {
        String what = e.key().isControl()
                ? "Our network via '" + e.key().docId() + "'"
                : e.key().source() + " / " + e.key().docId();
        String s = switch (e.type()) {
            case DOWN -> "[DOWN] " + what + " - " + e.category() + " since " + time.format(e.downSince());
            case REMINDER -> "[STILL DOWN #" + e.reminderNumber() + "] " + what + " - " + e.category()
                    + " for " + human(e.downFor());
            case CATEGORY_CHANGED -> "[DOWN - CAUSE CHANGED] " + what + " - now " + e.category()
                    + " (was " + e.previousCategory() + ")";
            case RECOVERED -> "[RECOVERED] " + what + " - back after " + human(e.downFor());
        };
        return masker.mask(catalog.mail().subjectPrefix() + " " + s);
    }

    public String body(AlertEvent e) {
        StringBuilder b = new StringBuilder();
        String api = e.key().isControl()
                ? "Connectivity check through '" + e.key().docId() + "' (our own network)"
                : e.key().source() + " / " + e.key().docId();

        if (e.type() == AlertEvent.Type.RECOVERED) {
            line(b, "API", api);
            line(b, "Status", "RECOVERED - responding normally again");
            line(b, "Was failing with", e.category() + " - " + e.category().description());
            line(b, "Down since", dateTime.format(e.downSince()));
            line(b, "Recovered at", dateTime.format(e.occurredAt()));
            line(b, "Total downtime", human(e.downFor()));
        } else {
            line(b, "API", api);
            line(b, "Status", e.type() == AlertEvent.Type.REMINDER ? "STILL DOWN" : "DOWN");
            line(b, "Cause", e.category() + " - " + e.category().description());
            if (e.previousCategory() != null) {
                line(b, "Previous cause", e.previousCategory() + " - " + e.previousCategory().description());
            }
            if (e.stage() != null) {
                line(b, "Failed step", e.stage() == Stage.AUTH ? "login / token call"
                        : e.stage() == Stage.CONTROL ? "reference URL check" : "API call");
            }
            ProbeDefinition def = e.key().isControl() ? null : catalog.probe(e.key());
            if (def != null) {
                line(b, "Endpoint", def.request().method() + " " + def.request().url());
            }
            if (e.httpStatus() != null) {
                line(b, "HTTP status", String.valueOf(e.httpStatus()));
            }
            line(b, "Detail", e.detail());
            line(b, "Down since", dateTime.format(e.downSince()) + " (" + human(e.downFor()) + ")");
            line(b, "Failed checks in a row", String.valueOf(e.consecutiveFailures()));
            if (def != null && catalog.controlCheck(def.proxy()) != null) {
                line(b, "Our connectivity", "verified OK through the same route before this alert was sent");
            }
            b.append('\n').append("What to do: ").append(ACTION.get(e.category())).append('\n');
            if (e.lastReminder()) {
                b.append("\nThis is the last reminder for this incident. You will still get an email when it recovers.\n");
            } else if (e.nextReminderAt() != null) {
                b.append("\nNext reminder at ").append(time.format(e.nextReminderAt()))
                        .append(" if it is still failing.\n");
            }
        }

        if (!e.recent().isEmpty()) {
            b.append("\nRecent checks (newest first):\n");
            for (CheckResult r : e.recent()) {
                b.append("  ").append(dateTime.format(r.at())).append("  ")
                        .append(r.success() ? "OK  " : "FAIL").append("  ")
                        .append(r.success() ? "" : r.category() + "  ")
                        .append(r.httpStatus() == null ? "" : "HTTP " + r.httpStatus() + "  ")
                        .append(r.latencyMs()).append(" ms")
                        .append(r.success() ? "" : "  " + r.detail())
                        .append('\n');
            }
        }
        b.append("\n-- enu-external-api-pulse (automated message)\n");
        return masker.mask(b.toString());
    }

    private static void line(StringBuilder b, String label, String value) {
        b.append(String.format("%-24s %s%n", label + ":", value == null ? "-" : value));
    }

    static String human(Duration d) {
        long minutes = Math.max(0, d.toMinutes());
        if (minutes < 1) {
            return Math.max(0, d.toSeconds()) + "s";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) {
            return m + "m";
        }
        long days = h / 24;
        return days > 0 ? days + "d " + (h % 24) + "h " + m + "m" : h + "h " + m + "m";
    }
}
