package com.uidai.enu.extapipulse.model;

/** Identity of a probe: the state (source) plus the document type. */
public record ProbeKey(String source, String docId) {

    public static ProbeKey control(String proxyName) {
        return new ProbeKey("CONTROL", proxyName);
    }

    public boolean isControl() {
        return "CONTROL".equals(source);
    }

    @Override
    public String toString() {
        return source + " / " + docId;
    }
}
