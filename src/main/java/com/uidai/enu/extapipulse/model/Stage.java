package com.uidai.enu.extapipulse.model;

/** Which request in a check failed. */
public enum Stage {
    AUTH,     // the login/token call
    MAIN,     // the actual API call
    CONTROL   // the reference URL used to tell our network problems from the vendor's
}
