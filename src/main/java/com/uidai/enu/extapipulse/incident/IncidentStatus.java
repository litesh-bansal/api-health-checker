package com.uidai.enu.extapipulse.incident;

public enum IncidentStatus {
    UNKNOWN,     // not checked yet
    UP,
    SUSPECT,     // failing, but fewer than failureThreshold times in a row
    DOWN,        // alerted
    RECOVERING   // was DOWN, passing, but fewer than recoveryThreshold times in a row
}
