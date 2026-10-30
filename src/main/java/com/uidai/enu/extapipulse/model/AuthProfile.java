package com.uidai.enu.extapipulse.model;

import java.time.Duration;
import java.util.Map;

/**
 * Resolved auth profile. Which fields matter depends on {@link #type()}:
 * API_KEY uses header/queryParam/value, BASIC uses username/password,
 * BEARER uses token, TOKEN_CALL uses request/expect/extract/apply.
 */
public record AuthProfile(
        String name,
        AuthType type,
        String header,
        String queryParam,
        String value,
        String username,
        String password,
        String token,
        RequestTemplate request,
        Expectation expect,
        Map<String, String> extract,
        Duration cacheTtl,
        boolean alwaysRefresh,
        Map<String, String> applyHeaders,
        Map<String, String> applyQuery
) {
    public static final String NONE_NAME = "none";

    public static AuthProfile none() {
        return new AuthProfile(NONE_NAME, AuthType.NONE, null, null, null, null, null, null,
                null, null, Map.of(), Duration.ZERO, false, Map.of(), Map.of());
    }
}
