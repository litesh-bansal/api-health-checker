package com.uidai.enu.extapipulse.model;

import java.time.Duration;
import java.util.List;

/** Fully resolved probe: source defaults and docId overrides already merged. */
public record ProbeDefinition(
        ProbeKey key,
        RequestTemplate request,
        Expectation expect,
        String authProfile,      // never null; "none" when no auth
        String proxy,            // never null; "none" when direct
        List<String> contacts,   // the API's own people (vendor / state team)
        Duration interval,
        Duration timeout,
        int retries,
        Duration retryBackoff,
        AlertingPolicy alerting
) {}
