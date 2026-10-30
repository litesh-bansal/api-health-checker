package com.uidai.enu.extapipulse.model;

import java.time.Duration;

/** Resolved throttling rules for one probe. */
public record AlertingPolicy(
        int failureThreshold,
        int recoveryThreshold,
        Duration reminderInterval,
        double reminderBackoff,
        Duration maxReminderInterval,
        int maxReminders,
        boolean sendRecoveryEmail
) {}
