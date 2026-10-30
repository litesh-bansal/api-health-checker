package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeKey;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything we remember about one probe between checks. Mutated only by
 * {@link IncidentTracker} while holding this object's lock.
 * Plain fields so a JDBC/Redis-backed {@link IncidentStore} can persist it as-is.
 */
public class IncidentState {

    static final int RECENT_KEEP = 5;

    final ProbeKey key;
    IncidentStatus status = IncidentStatus.UNKNOWN;
    int consecutiveFailures;
    int consecutiveSuccesses;
    Instant firstFailureAt;          // start of the current failure streak
    Instant downSince;               // set when DOWN is declared (= firstFailureAt)
    Instant lastAlertAt;
    int alertsSent;                  // DOWN + reminders + category changes, this incident
    int remindersSent;
    Duration currentReminderInterval;
    FailureCategory alertedCategory; // category of the last alert we sent
    FailureCategory lastCategory;    // category of the latest failure
    Instant lastCheckAt;
    boolean lastSuppressed;          // last failure was blamed on our network (control check failed)
    final Deque<CheckResult> recent = new ArrayDeque<>();
    final Set<String> notifiedRecipients = new LinkedHashSet<>();

    public IncidentState(ProbeKey key) {
        this.key = key;
    }

    void observe(CheckResult r, boolean suppressed) {
        lastCheckAt = r.at();
        lastSuppressed = suppressed;
        recent.addFirst(r);
        while (recent.size() > RECENT_KEEP) {
            recent.removeLast();
        }
    }

    /** Back to a clean UP state: counters, timers and backoff all reset. */
    void resetToUp() {
        status = IncidentStatus.UP;
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        firstFailureAt = null;
        downSince = null;
        lastAlertAt = null;
        alertsSent = 0;
        remindersSent = 0;
        currentReminderInterval = null;
        alertedCategory = null;
        lastCategory = null;
        notifiedRecipients.clear();
    }

    public synchronized IncidentSnapshot snapshot() {
        CheckResult last = recent.peekFirst();
        return new IncidentSnapshot(key, status, consecutiveFailures, consecutiveSuccesses, downSince,
                lastAlertAt, alertsSent, lastCategory, lastCheckAt, lastSuppressed,
                last == null ? null : last.httpStatus(), last == null ? null : last.latencyMs(),
                last == null ? null : last.detail(), List.copyOf(recent));
    }

    public ProbeKey key() {
        return key;
    }

    public record IncidentSnapshot(
            ProbeKey key,
            IncidentStatus status,
            int consecutiveFailures,
            int consecutiveSuccesses,
            Instant downSince,
            Instant lastAlertAt,
            int alertsSent,
            FailureCategory lastCategory,
            Instant lastCheckAt,
            boolean lastFailureBlamedOnOurNetwork,
            Integer lastHttpStatus,
            Long lastLatencyMs,
            String lastDetail,
            List<CheckResult> recent
    ) {}
}
