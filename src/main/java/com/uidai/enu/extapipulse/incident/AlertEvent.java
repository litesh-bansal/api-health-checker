package com.uidai.enu.extapipulse.incident;

import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.model.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Something worth telling people about. Produced by {@link IncidentTracker}, sent by the alert channels. */
public record AlertEvent(
        ProbeKey key,
        Type type,
        FailureCategory category,          // for RECOVERED: the category last alerted
        FailureCategory previousCategory,  // set for CATEGORY_CHANGED
        Stage stage,
        Integer httpStatus,
        String detail,
        Instant downSince,
        Instant occurredAt,
        int consecutiveFailures,
        int reminderNumber,                // 1, 2, ... for REMINDER
        boolean lastReminder,              // no more reminders will follow (maxReminders reached)
        Instant nextReminderAt,            // null when none is planned
        List<CheckResult> recent,
        Set<String> previouslyNotified     // for RECOVERED: everyone told about this incident
) {
    public enum Type { DOWN, REMINDER, CATEGORY_CHANGED, RECOVERED }

    public Duration downFor() {
        return downSince == null ? Duration.ZERO : Duration.between(downSince, occurredAt);
    }
}
