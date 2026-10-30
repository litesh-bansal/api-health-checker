package com.uidai.enu.extapipulse.auth;

import com.uidai.enu.extapipulse.http.HttpReply;

/** The login/token call answered, but not with something usable. */
public class AuthStepException extends Exception {

    public enum Kind {
        UNEXPECTED_RESPONSE, // wrong status or body check failed
        EXTRACT_FAILED       // healthy response, but an extract path was missing
    }

    private final Kind kind;
    private final transient HttpReply reply;

    public AuthStepException(Kind kind, HttpReply reply, String message) {
        super(message);
        this.kind = kind;
        this.reply = reply;
    }

    public Kind kind() {
        return kind;
    }

    public HttpReply reply() {
        return reply;
    }
}
