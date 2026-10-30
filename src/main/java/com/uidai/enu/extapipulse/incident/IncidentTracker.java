package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.AlertingPolicy;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Route;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The state machine that decides when an email is allowed.
 *
 * <pre>
 * UP --fail--> SUSPECT --fail x failureThreshold--> DOWN (alert)
 * DOWN --still failing--> reminder only every reminderInterval (optionally growing by reminderBackoff)
 * DOWN --cause changes to one routed to different people--> alert immediately
 * DOWN --pass--> RECOVERING --pass x recoveryThreshold--> UP (recovery email, everything reset)
 * RECOVERING --fail--> DOWN again, same incident, no new DOWN email (flapping protection)
 * </pre>
 */
public class IncidentTracker {

    private final IncidentStore store;
    private final Function<FailureCategory, Route> routeLookup;

    public IncidentTracker(IncidentStore store, Function<FailureCategory, Route> routeLookup) {
        this.store = store;
        this.routeLookup = routeLookup;
    }

    /** Feed one check result in; get back the alert to send, if any. */
    public Optional<AlertEvent> record(ProbeKey key, AlertingPolicy policy, CheckResult result) {
        IncidentState s = store.getOrCreate(key);
        synchronized (s) {
            s.observe(result, false);
            Optional<AlertEvent> event = result.success()
                    ? onSuccess(s, policy, result)
                    : onFailure(s, policy, result);
            store.save(s);
            return event;
        }
    }

    /**
     * The check failed, but the control check shows our own network is broken.
     * Remember it for the status page, but neither count it against the API nor alert.
     */
    public void recordSuppressed(ProbeKey key, CheckResult result) {
        IncidentState s = store.getOrCreate(key);
        synchronized (s) {
            s.observe(result, true);
            store.save(s);
        }
    }

    /** Remember who was told, so the recovery email reaches all of them. */
    public void addNotified(ProbeKey key, Set<String> recipients) {
        IncidentState s = store.getOrCreate(key);
        synchronized (s) {
            s.notifiedRecipients.addAll(recipients);
            store.save(s);
        }
    }

    private Optional<AlertEvent> onSuccess(IncidentState s, AlertingPolicy p, CheckResult r) {
        s.consecutiveFailures = 0;
        s.consecutiveSuccesses++;
        if (s.status == IncidentStatus.DOWN || s.status == IncidentStatus.RECOVERING) {
            if (s.consecutiveSuccesses < p.recoveryThreshold()) {
                s.status = IncidentStatus.RECOVERING;
                return Optional.empty();
            }
            AlertEvent recovered = null;
            if (p.sendRecoveryEmail() && s.alertsSent > 0) {
                recovered = new AlertEvent(s.key, AlertEvent.Type.RECOVERED, s.alertedCategory, null,
                        null, r.httpStatus(), "Responding normally again", s.downSince, r.at(), 0,
                        0, false, null, List.copyOf(s.recent), Set.copyOf(s.notifiedRecipients));
            }
            s.resetToUp();
            s.consecutiveSuccesses = 1;
            return Optional.ofNullable(recovered);
        }
        // UNKNOWN, UP or SUSPECT: a single pass clears a not-yet-alerted streak.
        s.status = IncidentStatus.UP;
        s.firstFailureAt = null;
        s.lastCategory = null;
        return Optional.empty();
    }

    private Optional<AlertEvent> onFailure(IncidentState s, AlertingPolicy p, CheckResult r) {
        s.consecutiveSuccesses = 0;
        s.consecutiveFailures++;
        if (s.firstFailureAt == null) {
            s.firstFailureAt = r.at();
        }
        s.lastCategory = r.category();

        switch (s.status) {
            case UNKNOWN, UP, SUSPECT -> {
                if (s.consecutiveFailures < p.failureThreshold()) {
                    s.status = IncidentStatus.SUSPECT;
                    return Optional.empty();
                }
                s.status = IncidentStatus.DOWN;
                s.downSince = s.firstFailureAt;
                s.alertsSent = 1;
                s.remindersSent = 0;
                s.alertedCategory = r.category();
                s.lastAlertAt = r.at();
                s.currentReminderInterval = p.reminderInterval();
                return Optional.of(event(s, p, r, AlertEvent.Type.DOWN, null));
            }
            case DOWN, RECOVERING -> {
                s.status = IncidentStatus.DOWN;

                // A different cause that goes to different people is a new problem: tell them now.
                if (!sameRoute(s.alertedCategory, r.category())) {
                    FailureCategory previous = s.alertedCategory;
                    s.alertedCategory = r.category();
                    s.alertsSent++;
                    s.remindersSent = 0;
                    s.lastAlertAt = r.at();
                    s.currentReminderInterval = p.reminderInterval();
                    return Optional.of(event(s, p, r, AlertEvent.Type.CATEGORY_CHANGED, previous));
                }
                s.alertedCategory = r.category();

                if (!reminderAllowed(s, p) || r.at().isBefore(s.lastAlertAt.plus(s.currentReminderInterval))) {
                    return Optional.empty();
                }
                s.remindersSent++;
                s.alertsSent++;
                s.lastAlertAt = r.at();
                s.currentReminderInterval = nextInterval(s.currentReminderInterval, p);
                return Optional.of(event(s, p, r, AlertEvent.Type.REMINDER, null));
            }
            default -> throw new IllegalStateException("Unhandled status " + s.status);
        }
    }

    private AlertEvent event(IncidentState s, AlertingPolicy p, CheckResult r,
                             AlertEvent.Type type, FailureCategory previous) {
        boolean last = p.maxReminders() > 0 && s.remindersSent >= p.maxReminders();
        Instant next = last ? null : s.lastAlertAt.plus(s.currentReminderInterval);
        return new AlertEvent(s.key, type, r.category(), previous, r.stage(), r.httpStatus(), r.detail(),
                s.downSince, r.at(), s.consecutiveFailures, s.remindersSent, last, next,
                List.copyOf(s.recent), Set.of());
    }

    private static boolean reminderAllowed(IncidentState s, AlertingPolicy p) {
        return p.maxReminders() <= 0 || s.remindersSent < p.maxReminders();
    }

    private static Duration nextInterval(Duration current, AlertingPolicy p) {
        if (p.reminderBackoff() <= 1.0) {
            return current;
        }
        Duration grown = Duration.ofMillis(Math.round(current.toMillis() * p.reminderBackoff()));
        Duration cap = p.maxReminderInterval().compareTo(p.reminderInterval()) > 0
                ? p.maxReminderInterval() : p.reminderInterval();
        return grown.compareTo(cap) > 0 ? cap : grown;
    }

    private boolean sameRoute(FailureCategory a, FailureCategory b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(routeLookup.apply(a), routeLookup.apply(b));
    }
}
