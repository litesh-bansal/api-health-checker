package com.uidai.enu.extapipulse.model;

import java.time.Instant;

/** Outcome of one check (after retries). */
public record CheckResult(
        ProbeKey key,
        Instant at,
        boolean success,
        FailureCategory category,  // null on success
        Stage stage,               // null on success
        Integer httpStatus,        // null when no response was received
        long latencyMs,
        String detail
) {
    public static CheckResult ok(ProbeKey key, Instant at, int status, long latencyMs) {
        return new CheckResult(key, at, true, null, null, status, latencyMs, "OK");
    }

    public static CheckResult failed(ProbeKey key, Instant at, FailureCategory category, Stage stage,
                                     Integer status, long latencyMs, String detail) {
        return new CheckResult(key, at, false, category, stage, status, latencyMs, detail);
    }
}
