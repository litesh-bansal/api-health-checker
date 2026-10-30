package com.uidai.enu.extapipulse.model;

import java.util.List;

/** Who hears about a failure category, and through which channels. */
public record Route(
        List<String> groups,
        List<String> emails,
        boolean apiContacts,
        List<String> channels
) {}
