package com.uidai.enu.extapipulse.http;

import java.time.Duration;
import java.util.Map;

/** A fully rendered request, ready to send (no placeholders left). */
public record HttpCall(
        String method,
        String url,          // query string already appended
        Map<String, String> headers,
        String body,         // null for no body
        Duration timeout
) {}
