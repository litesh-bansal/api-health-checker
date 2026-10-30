package com.uidai.enu.extapipulse.template;

/** A probe's configuration cannot be applied (missing secret, unknown placeholder...). */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }
}
