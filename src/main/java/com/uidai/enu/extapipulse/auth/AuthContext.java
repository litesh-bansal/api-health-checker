package com.uidai.enu.extapipulse.auth;

import java.util.Map;

/**
 * What auth contributes to the main request.
 *
 * @param vars      values extracted from the login call, usable as {{auth.NAME}}
 * @param headers   headers to add (already rendered)
 * @param query     query params to add (already rendered)
 * @param fromCache true if the token came from cache (a 401 then triggers one re-login)
 */
public record AuthContext(Map<String, String> vars, Map<String, String> headers,
                          Map<String, String> query, boolean fromCache) {

    public static AuthContext empty() {
        return new AuthContext(Map.of(), Map.of(), Map.of(), false);
    }
}
