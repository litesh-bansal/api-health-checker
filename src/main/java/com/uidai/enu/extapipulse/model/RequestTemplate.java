package com.uidai.enu.extapipulse.model;

import java.util.Map;

/** A request whose url, headers, query values and body may contain {{placeholders}}. */
public record RequestTemplate(
        String url,
        String method,
        Map<String, String> headers,
        Map<String, String> query,
        String body
) {}
