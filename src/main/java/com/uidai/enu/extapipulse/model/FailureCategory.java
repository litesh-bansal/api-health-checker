package com.uidai.enu.extapipulse.model;

/**
 * Why a check failed. Each category has its own routing entry in config,
 * which decides who is told and through which channels.
 */
public enum FailureCategory {
    // Our environment
    DNS("Host name could not be resolved"),
    PROXY("Proxy refused or failed the connection"),
    NETWORK("Network connection failed (refused, reset or connect timeout)"),
    CONFIG_ERROR("Probe configuration is wrong (missing secret, bad template)"),

    // Could be either side
    TLS("TLS/SSL handshake failed (expired certificate or our truststore)"),

    // Credentials / auth
    AUTH("Main call rejected our credentials (401/403)"),
    AUTH_REJECTED("Login/token call rejected our credentials"),
    AUTH_ENDPOINT_DOWN("Login/token endpoint is down or timing out"),
    AUTH_EXTRACT_FAILED("Login succeeded but the token was not found in the response"),

    // Vendor side
    VENDOR_DOWN("API returned a server error or timed out"),
    RATE_LIMITED("API is rate limiting us (429)"),
    SLOW_RESPONSE("API responded, but slower than the allowed limit"),
    CONTRACT("API responded, but not in the expected format");

    private final String description;

    FailureCategory(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
