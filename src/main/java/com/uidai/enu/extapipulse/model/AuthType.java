package com.uidai.enu.extapipulse.model;

import java.util.Locale;

public enum AuthType {
    NONE, API_KEY, BASIC, BEARER, TOKEN_CALL;

    public static AuthType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace("_", "-")) {
            case "none" -> NONE;
            case "apikey", "api-key" -> API_KEY;
            case "basic" -> BASIC;
            case "bearer" -> BEARER;
            case "token-call", "tokencall" -> TOKEN_CALL;
            default -> throw new IllegalArgumentException("Unknown auth type '" + raw
                    + "' (expected none, apiKey, basic, bearer or token-call)");
        };
    }
}
