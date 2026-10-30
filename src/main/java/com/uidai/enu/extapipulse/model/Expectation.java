package com.uidai.enu.extapipulse.model;

import java.util.Set;

/** What a healthy response looks like. */
public record Expectation(
        Set<Integer> statuses,
        String jsonPath,
        String equalsValue,
        boolean exists,
        String bodyContains,
        Long maxLatencyMs
) {
    public static Expectation status200() {
        return new Expectation(Set.of(200), null, null, false, null, null);
    }
}
