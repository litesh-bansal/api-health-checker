package com.uidai.enu.extapipulse.http;

import java.net.http.HttpHeaders;

/** A response we received (any status). */
public record HttpReply(int status, HttpHeaders headers, String body, long latencyMs) {

    private static final int SNIPPET = 300;

    /** Start of the body, single line, for logs and emails. */
    public String bodySnippet() {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET ? flat : flat.substring(0, SNIPPET) + "...";
    }
}
