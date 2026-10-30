package com.uidai.enu.extapipulse.alert;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.model.Route;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Route + the API's own contacts -> the actual email addresses. */
public class RecipientResolver {

    private final ProbeCatalog catalog;

    public RecipientResolver(ProbeCatalog catalog) {
        this.catalog = catalog;
    }

    public Set<String> resolve(Route route, List<String> apiContacts) {
        Set<String> out = new LinkedHashSet<>();
        for (String group : route.groups()) {
            catalog.recipientGroups().getOrDefault(group, List.of()).forEach(e -> out.add(normalize(e)));
        }
        route.emails().forEach(e -> out.add(normalize(e)));
        if (route.apiContacts() && apiContacts != null) {
            apiContacts.forEach(e -> out.add(normalize(e)));
        }
        return out;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
